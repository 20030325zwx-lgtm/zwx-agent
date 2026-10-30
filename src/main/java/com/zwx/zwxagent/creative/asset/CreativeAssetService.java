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

    public record AssetView(UUID id, UUID projectId, UUID jobId, String mediaType, String displayName,
                            boolean starred, String status, long byteSize,
                            Integer width, Integer height, Instant createdAt) {
    }

    public record AssetList(List<AssetView> items, String nextCursor) {
    }

    public record UpdateAssetRequest(String displayName, Boolean starred) {
    }

    private final CreativeAssetRepository assetRepository;
    private final CreativeProjectRepository projectRepository;
    private final MediaJobRepository jobRepository;
    private final MediaJobAssetRefRepository assetRefRepository;
    private final MediaStorage storage;

    public CreativeAssetService(CreativeAssetRepository assetRepository,
                                CreativeProjectRepository projectRepository,
                                MediaJobRepository jobRepository,
                                MediaJobAssetRefRepository assetRefRepository,
                                MediaStorage storage) {
        this.assetRepository = assetRepository;
        this.projectRepository = projectRepository;
        this.jobRepository = jobRepository;
        this.assetRefRepository = assetRefRepository;
        this.storage = storage;
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
                "{}", Instant.now(), null);
        assetRepository.insert(record);
        try {
            storage.put(objectKey, mimeOf(extension), new ByteArrayInputStream(bytes), bytes.length);
        } catch (RuntimeException exception) {
            assetRepository.softDelete(assetId); // 不残留 PENDING 孤儿（A14）
            throw new IllegalStateException("素材保存失败", exception);
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
                          String cursor, Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        var decoded = decodeCursor(cursor);
        List<com.zwx.zwxagent.creative.domain.CreativeAssetRecord> records =
                assetRepository.listForOwner(actor.tenantId(), actor.userId(), projectId,
                        mediaType, starred, decoded.createdAt(), decoded.id(), size + 1);
        List<AssetView> items = records.stream().limit(size)
                .map(CreativeAssetService::toView).toList();
        String nextCursor = null;
        if (records.size() > size) {
            var last = items.get(size - 1);
            nextCursor = last.createdAt().toEpochMilli() + ":" + last.id();
        }
        return new AssetList(items, nextCursor);
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
                record.width(), record.height(), record.createdAt());
    }
}
