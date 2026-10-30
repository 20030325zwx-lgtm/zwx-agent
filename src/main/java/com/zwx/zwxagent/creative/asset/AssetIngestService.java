package com.zwx.zwxagent.creative.asset;

import com.zwx.zwxagent.creative.domain.MediaJobOutputRecord;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;

/** 产物入库（C05 定义契约，C06 提供真实存储实现；Mock 实现仅写库不落对象）。 */
public interface AssetIngestService {

    /**
     * 把产物字节持久化并建立 READY 素材；必须幂等复用 output 槽位预分配的 assetId，
     * 重复调用不得产生第二个素材（A20）。
     */
    void ingest(MediaJobRecord job, MediaJobOutputRecord slot, String mimeType, byte[] bytes);
}
