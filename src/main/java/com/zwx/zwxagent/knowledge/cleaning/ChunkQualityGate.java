package com.zwx.zwxagent.knowledge.cleaning;

import com.zwx.zwxagent.knowledge.document.AgentKnowledgeBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片质量门禁：所有解析 provider（MinerU / 统一解析服务 / Docling 降级 / 本地兜底）的
 * 唯一汇聚点，在这里做切片级规范化与最小质量把关。
 *
 * <p>为什么要有这一层：块级清洗只覆盖产出 MineruBlock 的路径；Docling 直切与 markdown
 * 兜底不经过块解析，如果只做块级清洗，docx/txt 上传会完全绕过清洗。</p>
 *
 * <p>豁免规则（避免误杀有价值内容）：表格行切片、图注、图纸假设问题可以很短，
 * 不做最小长度判定；表格行也不参与整片去重（不同行共享表头属于正常结构）。</p>
 */
@Service
public class ChunkQualityGate {

    private static final Logger log = LoggerFactory.getLogger(ChunkQualityGate.class);

    private final CleaningSettings settings;

    public ChunkQualityGate(CleaningSettings settings) {
        this.settings = settings;
    }

    public ChunkGateResult filter(List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) return ChunkGateResult.passthrough(chunks);
        if (!settings.enabled()) return ChunkGateResult.passthrough(chunks);

        List<Document> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int charsBefore = 0;
        int charsAfter = 0;
        int droppedTooShort = 0;
        int droppedNoise = 0;
        int droppedDuplicates = 0;

        for (Document chunk : chunks) {
            String original = chunk.getText() == null ? "" : chunk.getText();
            charsBefore += original.length();
            String text = TextNormalizer.normalizeChunkText(original);
            if (text.isBlank() || NoiseDetector.isDroppableNoise(text)) {
                droppedNoise++;
                continue;
            }
            boolean exempt = isExempt(chunk);
            if (!exempt && text.length() < settings.minChunkChars()) {
                droppedTooShort++;
                continue;
            }
            if (settings.dedupeEnabled() && !isTableRecord(chunk)
                    && text.length() >= settings.dedupeMinChars() && !seen.add(sha256(text))) {
                droppedDuplicates++;
                continue;
            }
            charsAfter += text.length();
            kept.add(text.equals(original) ? chunk : rebuild(chunk, text));
        }

        ChunkGateStats stats = new ChunkGateStats(chunks.size(), kept.size(), droppedTooShort,
                droppedNoise, droppedDuplicates, charsBefore, charsAfter);
        if (droppedTooShort + droppedNoise + droppedDuplicates > 0) {
            log.info("切片门禁：in={}, out={}, tooShort={}, noise={}, duplicate={}",
                    stats.chunksIn(), stats.chunksOut(), droppedTooShort, droppedNoise, droppedDuplicates);
        }
        return new ChunkGateResult(kept, stats);
    }

    private boolean isExempt(Document chunk) {
        return isTableRecord(chunk) || isFigureChunk(chunk);
    }

    private boolean isTableRecord(Document chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        return AgentKnowledgeBlock.TABLE.equals(String.valueOf(metadata.get("sourceType")))
                || "table-record".equals(String.valueOf(metadata.get("structuredContentType")));
    }

    private boolean isFigureChunk(Document chunk) {
        Object role = chunk.getMetadata().get("chunkRole");
        return role != null && String.valueOf(role).startsWith("figure_");
    }

    private Document rebuild(Document chunk, String text) {
        return Document.builder()
                .id(chunk.getId())
                .text(text)
                .metadata(new HashMap<>(chunk.getMetadata()))
                .build();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
