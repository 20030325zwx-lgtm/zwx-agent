package com.zwx.zwxagent.creative.asset;

import com.zwx.zwxagent.creative.domain.AssetOrigin;
import com.zwx.zwxagent.creative.domain.AssetStatus;
import com.zwx.zwxagent.creative.domain.CreativeAssetRecord;
import com.zwx.zwxagent.creative.domain.MediaJobOutputRecord;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.MediaJobOutputRepository;
import com.zwx.zwxagent.creative.storage.MediaStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;

/**
 * 真实入库（C06/C07）：先写对象存储，再落素材记录，最后标记产物槽位。
 * 幂等（A20）：槽位已预分配 assetId 与 objectKey；重试覆盖同一对象并复用同一素材行，
 * 因此「对象写完但数据库失败」可再次进入 FETCHING 而不产生重复素材。
 */
@Component
@ConditionalOnProperty(name = "creative.ingest.backend", havingValue = "storage", matchIfMissing = true)
public class StorageAssetIngestService implements AssetIngestService {

    private final CreativeAssetRepository assetRepository;
    private final MediaJobOutputRepository outputRepository;
    private final MediaStorage storage;

    public StorageAssetIngestService(CreativeAssetRepository assetRepository,
                                     MediaJobOutputRepository outputRepository,
                                     MediaStorage storage) {
        this.assetRepository = assetRepository;
        this.outputRepository = outputRepository;
        this.storage = storage;
    }

    @Override
    public void ingest(MediaJobRecord job, MediaJobOutputRecord slot, String mimeType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("产物为空，拒绝入库");
        }
        String contentType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
        boolean video = com.zwx.zwxagent.creative.job.MediaJobScheduler.isVideoOperation(job.operation());
        Optional<CreativeAssetRecord> existing = assetRepository.findByIdForOwner(
                job.tenantId(), job.userId(), slot.assetId());
        // 对象键由槽位固定，重试覆盖同一对象，不会产生第二份产物
        storage.put(slot.objectKey(), contentType, new ByteArrayInputStream(bytes), bytes.length);
        if (!video) {
            CreativeAssetService.putThumbnailBestEffort(storage, slot.objectKey(), contentType, bytes);
        }
        String sha256 = MockAssetIngestService.sha256(bytes);
        String mediaType = video ? "VIDEO" : "IMAGE";
        String displayName = video ? "生成视频" : "生成图片";
        // 尺寸/时长解析是增强信息：解析失败不能让已生成的产物丢失
        Integer width = null;
        Integer height = null;
        Long durationMs = null;
        if (video) {
            // 视频探测尽力而为（MVHD/TKHD）；无 ffmpeg 运行时不阻断 READY，封面由播放器原生首帧承担
            try {
                var inspection = com.zwx.zwxagent.creative.video.VideoProbe.inspect(bytes);
                width = inspection.width();
                height = inspection.height();
                durationMs = inspection.durationMs();
                org.slf4j.LoggerFactory.getLogger(StorageAssetIngestService.class).info(
                        "video ingest probe: bytes={} supported={} w={} h={} dur={}",
                        bytes.length, inspection.supported(), width, height, durationMs);
            } catch (RuntimeException ignored) {
                // 保留 null 尺寸/时长
            }
        } else {
            try {
                var inspection = com.zwx.zwxagent.creative.asset.ImageInspector.inspect(bytes);
                if (inspection.supported()) {
                    width = inspection.width();
                    height = inspection.height();
                }
            } catch (RuntimeException ignored) {
                // 保留 null 尺寸
            }
        }
        if (existing.isEmpty()) {
            assetRepository.insert(new CreativeAssetRecord(
                    slot.assetId(), job.projectId(), job.tenantId(), job.userId(),
                    job.id(), slot.outputIndex(), AssetOrigin.GENERATED,
                    mediaType, storageBackend(), slot.objectKey(),
                    contentType, bytes.length, sha256,
                    width, height, durationMs,
                    displayName, false, AssetStatus.READY,
                    null, "{}", Instant.now(), null));
        }
        outputRepository.markDone(slot.id(), sha256, (long) bytes.length);
    }

    private com.zwx.zwxagent.creative.domain.StorageBackend storageBackend() {
        return "LOCAL".equalsIgnoreCase(storage.backend())
                ? com.zwx.zwxagent.creative.domain.StorageBackend.LOCAL
                : com.zwx.zwxagent.creative.domain.StorageBackend.OSS;
    }
}
