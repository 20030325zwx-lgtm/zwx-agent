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
        Optional<CreativeAssetRecord> existing = assetRepository.findByIdForOwner(
                job.tenantId(), job.userId(), slot.assetId());
        // 对象键由槽位固定，重试覆盖同一对象，不会产生第二份产物
        storage.put(slot.objectKey(), contentType, new ByteArrayInputStream(bytes), bytes.length);
        String sha256 = MockAssetIngestService.sha256(bytes);
        // 尺寸解析是增强信息：解析失败不能让已生成的图片丢失
        Integer width = null;
        Integer height = null;
        try {
            var inspection = com.zwx.zwxagent.creative.asset.ImageInspector.inspect(bytes);
            if (inspection.supported()) {
                width = inspection.width();
                height = inspection.height();
            }
        } catch (RuntimeException ignored) {
            // 保留 null 尺寸
        }
        if (existing.isEmpty()) {
            assetRepository.insert(new CreativeAssetRecord(
                    slot.assetId(), job.projectId(), job.tenantId(), job.userId(),
                    job.id(), slot.outputIndex(), AssetOrigin.GENERATED,
                    "IMAGE", storageBackend(), slot.objectKey(),
                    contentType, bytes.length, sha256,
                    width, height, null,
                    "生成图片", false, AssetStatus.READY,
                    "{}", Instant.now(), null));
        }
        outputRepository.markDone(slot.id(), sha256, (long) bytes.length);
    }

    private com.zwx.zwxagent.creative.domain.StorageBackend storageBackend() {
        return "LOCAL".equalsIgnoreCase(storage.backend())
                ? com.zwx.zwxagent.creative.domain.StorageBackend.LOCAL
                : com.zwx.zwxagent.creative.domain.StorageBackend.OSS;
    }
}
