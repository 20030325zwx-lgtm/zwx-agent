package com.zwx.zwxagent.creative.asset;

import com.zwx.zwxagent.creative.domain.AssetOrigin;
import com.zwx.zwxagent.creative.domain.AssetStatus;
import com.zwx.zwxagent.creative.domain.CreativeAssetRecord;
import com.zwx.zwxagent.creative.domain.IngestState;
import java.time.Instant;
import com.zwx.zwxagent.creative.domain.MediaJobOutputRecord;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.domain.StorageBackend;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.MediaJobOutputRepository;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Mock 入库（C05）：只写素材记录与产物槽位，不落对象存储。
 * 默认入库实现是 {@link StorageAssetIngestService}；仅 creative.ingest.backend=mock 时启用本实现。
 * 幂等：素材已存在时校验后直接完成（A20 复用同一 assetId/objectKey）。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "creative.ingest.backend", havingValue = "mock")
public class MockAssetIngestService implements AssetIngestService {

    private final CreativeAssetRepository assetRepository;
    private final MediaJobOutputRepository outputRepository;

    public MockAssetIngestService(CreativeAssetRepository assetRepository,
                                  MediaJobOutputRepository outputRepository) {
        this.assetRepository = assetRepository;
        this.outputRepository = outputRepository;
    }

    @Override
    public void ingest(MediaJobRecord job, MediaJobOutputRecord slot, String mimeType, byte[] bytes) {
        String sha256 = sha256(bytes);
        Optional<CreativeAssetRecord> existing = assetRepository.findByIdForOwner(
                job.tenantId(), job.userId(), slot.assetId());
        if (existing.isEmpty()) {
            assetRepository.insert(new CreativeAssetRecord(
                    slot.assetId(), job.projectId(), job.tenantId(), job.userId(),
                    job.id(), slot.outputIndex(), AssetOrigin.GENERATED,
                    "IMAGE", StorageBackend.LOCAL, slot.objectKey(),
                    mimeType, bytes.length, sha256,
                    null, null, null,
                    "生成图片", false, AssetStatus.READY,
                    "{}", Instant.now(), null));
        }
        outputRepository.markDone(slot.id(), sha256, (long) bytes.length);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("摘要计算失败", exception);
        }
    }
}
