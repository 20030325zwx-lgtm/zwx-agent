package com.zwx.zwxagent.creative.asset;

import com.zwx.zwxagent.creative.domain.AssetStatus;
import com.zwx.zwxagent.creative.domain.CreativeAssetRecord;
import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.ProjectStatus;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.CreativeProjectRepository;
import com.zwx.zwxagent.creative.repository.MediaJobAssetRefRepository;
import com.zwx.zwxagent.creative.repository.MediaJobRepository;
import com.zwx.zwxagent.creative.storage.MediaStorage;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 素材生命周期（C06）：上传校验（签名/大小/像素）→ 存储 → READY；
 * 内容读取按归属授权；删除为软删除且被活动任务引用时 409（BR14/BR16）。
 */
@Service
public class CreativeAssetService {

    public static final int MAX_BYTES = 20 * 1024 * 1024;
    public static final long MAX_PIXELS = 40_000_000L;
    /** 视频上传默认预算 100MiB：视频文件远大于图片，不能沿用图片 20MiB（配置 creative.video.upload-max-bytes）。 */
    private static final long DEFAULT_VIDEO_MAX_BYTES = 100L * 1024 * 1024;

    public record AssetView(UUID id, UUID projectId, UUID jobId, String mediaType, String displayName,
                            boolean starred, String status, long byteSize,
                            Integer width, Integer height, Long durationMs, Instant createdAt, String origin,
                            String businessType) {
    }

    public record AssetList(List<AssetView> items, String nextCursor) {
    }

    /** 使用位置：镜头候选（执行位置）与角色/地点参考图（业务位置）。 */
    public record UsageShot(String kind, UUID candidateId, UUID shotId, UUID projectId, String status,
                            int episodeNumber, String episodeTitle, int sceneNumber, int shotNumber) {
    }

    public record UsageRef(String entityType, UUID entityId, UUID projectId, String name) {
    }

    public record AssetUsage(List<UsageShot> shots, List<UsageRef> characterRefs, List<UsageRef> locationRefs) {
    }

    public record UpdateAssetRequest(String displayName, Boolean starred, String businessType) {
    }

    private final CreativeAssetRepository assetRepository;
    private final CreativeProjectRepository projectRepository;
    private final MediaJobRepository jobRepository;
    private final MediaJobAssetRefRepository assetRefRepository;
    private final MediaStorage storage;
    private final com.zwx.zwxagent.creative.repository.VideoShotCandidateRepository candidateRepository;
    private final com.zwx.zwxagent.creative.repository.VideoStoryResourceRepository resourceRepository;
    private final long videoMaxBytes;

    public CreativeAssetService(CreativeAssetRepository assetRepository,
                                CreativeProjectRepository projectRepository,
                                MediaJobRepository jobRepository,
                                MediaJobAssetRefRepository assetRefRepository,
                                MediaStorage storage,
                                com.zwx.zwxagent.creative.repository.VideoShotCandidateRepository candidateRepository,
                                com.zwx.zwxagent.creative.repository.VideoStoryResourceRepository resourceRepository,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${creative.video.upload-max-bytes:104857600}") long videoMaxBytes) {
        this.assetRepository = assetRepository;
        this.projectRepository = projectRepository;
        this.jobRepository = jobRepository;
        this.assetRefRepository = assetRefRepository;
        this.storage = storage;
        this.candidateRepository = candidateRepository;
        this.resourceRepository = resourceRepository;
        this.videoMaxBytes = videoMaxBytes > 0 ? videoMaxBytes : DEFAULT_VIDEO_MAX_BYTES;
    }

    /** 上传：校验 → 写对象 → 建 PENDING 素材 → READY。失败不残留正式素材（A14）。 */
    public AssetView upload(CurrentActor actor, UUID projectId, String filename, byte[] bytes) {
        var project = projectRepository.findByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new IllegalStateException("项目已归档，不能上传素材");
        }
        if (!com.zwx.zwxagent.creative.asset.ImageInspector.withinByteBudget(
                bytes == null ? 0 : bytes.length)) {
            throw new IllegalArgumentException("图片超过 20MiB 上限");
        }
        var inspection = com.zwx.zwxagent.creative.asset.ImageInspector.inspect(bytes);
        if (!inspection.supported()) {
            throw new IllegalArgumentException("仅支持 PNG/JPEG/WebP 图片");
        }
        if (!inspection.withinPixelBudget()) {
            throw new IllegalArgumentException("图片像素超过 40MP 上限");
        }
        String extension = switch (inspection.type()) {
            case PNG -> "png";
            case JPEG -> "jpg";
            default -> "webp";
        };
        UUID assetId = UUID.randomUUID();
        String objectKey = "creative/" + actor.tenantId() + "/" + actor.userId() + "/"
                + projectId + "/" + assetId + "/original." + extension;
        var record = new CreativeAssetRecord(assetId, projectId, actor.tenantId(), actor.userId(),
                null, null, com.zwx.zwxagent.creative.domain.AssetOrigin.UPLOAD,
                "IMAGE", com.zwx.zwxagent.creative.domain.StorageBackend.valueOf(storage.backend()),
                objectKey, mimeOf(extension), bytes.length,
                com.zwx.zwxagent.creative.asset.MockAssetIngestService.sha256(bytes),
                inspection.width(), inspection.height(), null,
                displayNameOf(filename), false, com.zwx.zwxagent.creative.domain.AssetStatus.PENDING,
                null, "{}", Instant.now(), null);
        assetRepository.insert(record);
        try {
            storage.put(objectKey, mimeOf(extension), new ByteArrayInputStream(bytes), bytes.length);
            putThumbnailBestEffort(objectKey, bytes);
        } catch (RuntimeException exception) {
            assetRepository.softDelete(assetId); // 不残留 PENDING 孤儿（A14）
            throw new IllegalStateException("素材保存失败", exception);
        }
        assetRepository.markReady(assetId, inspection.width(), inspection.height(), null);
        return toView(assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId).orElseThrow());
    }

    /**
     * 视频上传（V1-A）：魔数判定（不信任扩展名）→ MP4/MOV/WebM → VideoProbe 尽力探测时长画幅 → READY。
     * 视频不生成缩略图（无 ffmpeg 抽帧），封面由播放器原生首帧承担。
     */
    public AssetView uploadVideo(CurrentActor actor, UUID projectId, String filename, byte[] bytes) {
        var project = projectRepository.findByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new IllegalStateException("项目已归档，不能上传素材");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("视频内容为空");
        }
        if (bytes.length > videoMaxBytes) {
            throw new IllegalArgumentException("视频超过 " + (videoMaxBytes / 1024 / 1024) + "MiB 上限");
        }
        String extension;
        String mimeType;
        if (com.zwx.zwxagent.creative.video.VideoProbe.isMp4(bytes)) {
            // ftyp major brand "qt  " 是 QuickTime 容器，其余按 mp4 处理
            boolean quicktime = new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("qt  ");
            extension = quicktime ? "mov" : "mp4";
            mimeType = quicktime ? "video/quicktime" : "video/mp4";
        } else if (bytes.length >= 4 && (bytes[0] & 0xff) == 0x1A && (bytes[1] & 0xff) == 0x45
                && (bytes[2] & 0xff) == 0xDF && (bytes[3] & 0xff) == 0xA3) {
            extension = "webm";
            mimeType = "video/webm";
        } else {
            throw new IllegalArgumentException("仅支持 MP4/MOV/WebM 视频");
        }
        Integer width = null;
        Integer height = null;
        Long durationMs = null;
        if ("mp4".equals(extension) || "mov".equals(extension)) {
            // 探测尽力而为：失败不阻断上传，时长画幅留空由播放器自行解析
            try {
                var inspection = com.zwx.zwxagent.creative.video.VideoProbe.inspect(bytes);
                width = inspection.width();
                height = inspection.height();
                durationMs = inspection.durationMs();
            } catch (RuntimeException ignored) {
                // 保留 null 探测结果
            }
        }
        UUID assetId = UUID.randomUUID();
        String objectKey = "creative/" + actor.tenantId() + "/" + actor.userId() + "/"
                + projectId + "/" + assetId + "/original." + extension;
        var record = new CreativeAssetRecord(assetId, projectId, actor.tenantId(), actor.userId(),
                null, null, com.zwx.zwxagent.creative.domain.AssetOrigin.UPLOAD,
                "VIDEO", com.zwx.zwxagent.creative.domain.StorageBackend.valueOf(storage.backend()),
                objectKey, mimeType, bytes.length,
                com.zwx.zwxagent.creative.asset.MockAssetIngestService.sha256(bytes),
                width, height, durationMs,
                displayNameOf(filename), false, com.zwx.zwxagent.creative.domain.AssetStatus.PENDING,
                null, "{}", Instant.now(), null);
        assetRepository.insert(record);
        try {
            storage.put(objectKey, mimeType, new ByteArrayInputStream(bytes), bytes.length);
        } catch (RuntimeException exception) {
            assetRepository.softDelete(assetId); // 不残留 PENDING 孤儿（A14）
            throw new IllegalStateException("视频保存失败", exception);
        }
        assetRepository.markReady(assetId, width, height, durationMs);
        return toView(assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId).orElseThrow());
    }

    /**
     * 工作流渲染产物登记（R05，2026-10-31 评审）：调用方已把字节写入 objectKey（mediaStorage 适配本地/OSS），
     * 这里补齐校验、素材行（origin=DERIVED）与缩略图。失败即抛错，由调用方把节点置为可重试失败——
     * 渲染是确定性且零计费的，重试不会重新调用模型。
     */
    public AssetView registerRendered(CurrentActor actor, UUID projectId, String displayName,
                                      byte[] bytes, String objectKey, java.util.Map<String, Object> metadata) {
        var project = projectRepository.findByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new IllegalStateException("项目已归档，不能登记素材");
        }
        if (!com.zwx.zwxagent.creative.asset.ImageInspector.withinByteBudget(bytes == null ? 0 : bytes.length)) {
            throw new IllegalArgumentException("渲染产物超过 20MiB 上限");
        }
        var inspection = com.zwx.zwxagent.creative.asset.ImageInspector.inspect(bytes);
        if (!inspection.supported()) {
            throw new IllegalArgumentException("渲染产物不是受支持的图片格式");
        }
        if (!inspection.withinPixelBudget()) {
            throw new IllegalArgumentException("渲染产物像素超过 40MP 上限");
        }
        String mimeType = switch (inspection.type()) {
            case PNG -> "image/png";
            case JPEG -> "image/jpeg";
            default -> "image/webp";
        };
        UUID assetId = UUID.randomUUID();
        String metadataJson = metadata == null || metadata.isEmpty() ? "{}"
                : com.zwx.zwxagent.creative.support.CreativeJson.toJson(metadata);
        var record = new CreativeAssetRecord(assetId, projectId, actor.tenantId(), actor.userId(),
                null, null, com.zwx.zwxagent.creative.domain.AssetOrigin.DERIVED,
                "IMAGE", com.zwx.zwxagent.creative.domain.StorageBackend.valueOf(storage.backend()),
                objectKey, mimeType, bytes.length,
                com.zwx.zwxagent.creative.asset.MockAssetIngestService.sha256(bytes),
                inspection.width(), inspection.height(), null,
                displayNameOf(displayName), false, com.zwx.zwxagent.creative.domain.AssetStatus.PENDING,
                null, metadataJson, Instant.now(), null);
        assetRepository.insert(record);
        try {
            putThumbnailBestEffort(objectKey, bytes);
        } catch (RuntimeException exception) {
            assetRepository.softDelete(assetId);
            throw new IllegalStateException("渲染产物缩略图生成失败", exception);
        }
        assetRepository.markReady(assetId, inspection.width(), inspection.height(), null);
        return toView(assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId).orElseThrow());
    }

    /** 内容读取（BR16）：仅本人；流式返回，不暴露对象键与磁盘路径。 */
    public ContentContent content(CurrentActor actor, UUID assetId) {
        var asset = requireOwned(actor, assetId);
        if (asset.status() == com.zwx.zwxagent.creative.domain.AssetStatus.DELETED) {
            throw new IllegalArgumentException("素材已删除");
        }
        byte[] bytes;
        try (var in = storage.open(asset.objectKey())) {
            bytes = in.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("素材读取失败", exception);
        }
        return new ContentContent(asset.mimeType(), asset.displayName(), bytes);
    }

    public record ContentContent(String mimeType, String displayName, byte[] bytes) {
    }

    /** 视频流式播放元数据：大小与媒体类型，供 Range/206 分块响应。 */
    public record PlaybackMeta(String mimeType, String displayName, long size) {
    }

    /** 流式打开素材内容（BR16 授权后调用）；调用方负责关闭流。视频播放必须走此路径。 */
    public PlaybackMeta playbackMeta(CurrentActor actor, UUID assetId) {
        var asset = requireOwned(actor, assetId);
        if (asset.status() == com.zwx.zwxagent.creative.domain.AssetStatus.DELETED) {
            throw new IllegalArgumentException("素材已删除");
        }
        return new PlaybackMeta(asset.mimeType(), asset.displayName(), asset.byteSize());
    }

    public java.io.InputStream openStream(CurrentActor actor, UUID assetId) {
        var asset = requireOwned(actor, assetId);
        if (asset.status() == com.zwx.zwxagent.creative.domain.AssetStatus.DELETED) {
            throw new IllegalArgumentException("素材已删除");
        }
        try {
            return storage.open(asset.objectKey());
        } catch (Exception exception) {
            throw new IllegalStateException("素材读取失败", exception);
        }
    }

    /** 缩略图读取（A17）：没有缩略图时返回 null，由接口给 404，界面回退到原图。 */
    public byte[] thumbnail(CurrentActor actor, UUID assetId) {
        var asset = requireOwned(actor, assetId);
        if (asset.status() == com.zwx.zwxagent.creative.domain.AssetStatus.DELETED) {
            return null;
        }
        try (var in = storage.open(ThumbnailEncoder.thumbnailKey(asset.objectKey()))) {
            return in.readAllBytes();
        } catch (Exception exception) {
            return null;
        }
    }

    /** 缩略图失败不影响原件：生成失败只记录日志级别信息并继续。 */
    public static void putThumbnailBestEffort(MediaStorage storage, String objectKey, String mimeType,
                                              byte[] bytes) {
        byte[] thumbnail = ThumbnailEncoder.encode(bytes);
        if (thumbnail == null) return;
        try {
            storage.put(ThumbnailEncoder.thumbnailKey(objectKey),
                    "image/png", new ByteArrayInputStream(thumbnail), thumbnail.length);
        } catch (RuntimeException ignored) {
            // 缩略图是增强信息，不阻断入库
        }
    }

    private void putThumbnailBestEffort(String objectKey, byte[] bytes) {
        putThumbnailBestEffort(storage, objectKey, "image/png", bytes);
    }

    public AssetView update(CurrentActor actor, UUID assetId, UpdateAssetRequest request) {
        var asset = requireOwned(actor, assetId);
        if (asset.status() == com.zwx.zwxagent.creative.domain.AssetStatus.DELETED) {
            throw new IllegalArgumentException("素材已删除");
        }
        String displayName = request == null || request.displayName() == null
                ? asset.displayName() : request.displayName();
        boolean starred = request == null || request.starred() == null
                ? asset.starred() : request.starred();
        if (!assetRepository.updateDisplay(assetId, displayName, starred)) {
            throw new IllegalStateException("素材更新失败");
        }
        if (request != null && request.businessType() != null) {
            var type = com.zwx.zwxagent.creative.domain.AssetBusinessType.parse(request.businessType());
            assetRepository.updateBusinessType(assetId, type == null ? null : type.name());
        }
        return toView(assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId).orElseThrow());
    }

    /** 软删除（BR14）：被活动任务引用时 409；不删除对象（物理清理另做）。 */
    public void delete(CurrentActor actor, UUID assetId) {
        requireOwned(actor, assetId);
        if (assetRefRepository.countActiveJobRefs(assetId) > 0) {
            throw new com.zwx.zwxagent.creative.domain.CreativeConflictException(
                    "素材正被进行中的任务引用，暂不能删除");
        }
        assetRepository.softDelete(assetId);
    }

    public AssetList list(CurrentActor actor, UUID projectId, String mediaType, boolean starred,
                          String origin, String businessType, boolean trashed, String cursor, Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        var decoded = decodeCursor(cursor);
        var type = com.zwx.zwxagent.creative.domain.AssetBusinessType.parse(businessType);
        List<com.zwx.zwxagent.creative.domain.CreativeAssetRecord> records =
                assetRepository.listForOwner(actor.tenantId(), actor.userId(), projectId,
                        mediaType, starred, origin, type == null ? null : type.name(), trashed,
                        decoded.createdAt(), decoded.id(), size + 1);
        List<AssetView> items = records.stream().limit(size)
                .map(CreativeAssetService::toView).toList();
        String nextCursor = null;
        if (records.size() > size) {
            var last = items.get(size - 1);
            nextCursor = last.createdAt().toEpochMilli() + ":" + last.id();
        }
        return new AssetList(items, nextCursor);
    }

    /** 回收站恢复（BR14 的逆操作）：只有已软删除的素材可以恢复。 */
    public AssetView restore(CurrentActor actor, UUID assetId) {
        var asset = requireOwned(actor, assetId);
        if (asset.status() != com.zwx.zwxagent.creative.domain.AssetStatus.DELETED) {
            throw new IllegalArgumentException("素材不在回收站");
        }
        if (!assetRepository.restore(assetId)) {
            throw new IllegalStateException("恢复失败，请刷新后重试");
        }
        return toView(assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId).orElseThrow());
    }

    /** 使用位置反查：素材被哪些镜头候选、角色/地点参考引用；跨项目。 */
    public AssetUsage usage(CurrentActor actor, UUID assetId) {
        requireOwned(actor, assetId);
        List<UsageShot> shots = candidateRepository
                .listUsageByAsset(actor.tenantId(), actor.userId(), assetId).stream()
                .map(row -> new UsageShot("SHOT", row.candidateId(), row.shotId(), row.projectId(), row.status(),
                        row.episodeNumber(), row.episodeTitle(), row.sceneNumber(), row.shotNumber()))
                .toList();
        List<UsageRef> refs = resourceRepository.listRefsByAsset(actor.tenantId(), actor.userId(), assetId).stream()
                .map(row -> new UsageRef(row.entityType(), row.entityId(), row.projectId(), row.name()))
                .toList();
        List<UsageRef> characterRefs = refs.stream().filter(ref -> "CHARACTER".equals(ref.entityType())).toList();
        List<UsageRef> locationRefs = refs.stream().filter(ref -> "LOCATION".equals(ref.entityType())).toList();
        return new AssetUsage(shots, characterRefs, locationRefs);
    }

    private CreativeAssetRecord requireOwned(CurrentActor actor, UUID assetId) {
        return assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在"));
    }

    private static String displayNameOf(String filename) {
        String value = filename == null || filename.isBlank() ? "上传图片" : filename.trim();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private static String mimeOf(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            default -> "image/webp";
        };
    }

    public record Cursor(Instant createdAt, UUID id) {
    }

    public static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new Cursor(null, null);
        String[] parts = cursor.split(":", 2);
        try {
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (Exception exception) {
            throw new IllegalArgumentException("分页游标格式不正确");
        }
    }

    private static AssetView toView(CreativeAssetRecord record) {
        return new AssetView(record.id(), record.projectId(), record.sourceJobId(), record.mediaType(), record.displayName(),
                record.starred(), record.status().name(), record.byteSize(),
                record.width(), record.height(), record.durationMs(), record.createdAt(), record.origin().name(),
                record.businessType());
    }
}
