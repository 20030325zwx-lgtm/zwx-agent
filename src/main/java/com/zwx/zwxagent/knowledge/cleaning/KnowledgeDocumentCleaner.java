package com.zwx.zwxagent.knowledge.cleaning;

import com.zwx.zwxagent.knowledge.document.AgentKnowledgeBlock;
import com.zwx.zwxagent.knowledge.document.MineruBlockParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库文档清洗（规则层）：在 MinerU/统一解析结果落库与切片之间，对来源块做
 * “格式修复 + 噪声剔除 + 段落合并 + 完全重复去除”，并保留每个块的处理记录。
 *
 * <p>安全边界（防止清洗把知识库洗坏）：</p>
 * <ol>
 *   <li><b>只清洗 TEXT 块</b>：表格保留 MinerU 结构化结果与 HTML；公式保留原始表示；
 *       图片块保留引用与图注——它们都不参与空白/换行/去重规则。</li>
 *   <li><b>删除比例兜底</b>：块级字符删除率超过 {@code max-deletion-ratio} 时整体回滚，
 *       返回原始块（宁可脏一点，也不接受规则误杀整篇文档）。</li>
 *   <li><b>疑罪从无</b>：乱码只打 reviewRequired 标记，不删除。</li>
 *   <li><b>页眉页脚只按跨页高频判定</b>，标题块不参与；丢弃动作写入清洗记录，可审计。</li>
 * </ol>
 */
@Service
public class KnowledgeDocumentCleaner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentCleaner.class);

    /** 比例回滚只对“有统计意义”的文档生效：短文档的绝对损失很小，按比例回滚反而会放大误判。 */
    private static final int MIN_ROLLBACK_CHARS = 200;

    private final CleaningSettings settings;
    private final FurnitureDetector furnitureDetector;
    private final ParagraphMerger paragraphMerger;

    public KnowledgeDocumentCleaner(CleaningSettings settings, FurnitureDetector furnitureDetector,
                                    ParagraphMerger paragraphMerger) {
        this.settings = settings;
        this.furnitureDetector = furnitureDetector;
        this.paragraphMerger = paragraphMerger;
    }

    public BlockCleaningResult clean(List<MineruBlockParser.MineruBlock> blocks) {
        if (blocks == null || blocks.isEmpty() || !settings.enabled()) {
            return BlockCleaningResult.passthrough(blocks);
        }
        Set<String> furniture = furnitureDetector.detect(blocks);
        Map<String, CleanedBlock> records = new LinkedHashMap<>();
        List<MineruBlockParser.MineruBlock> normalized = new ArrayList<>();
        int charsBefore = 0;
        int textBlocks = 0;
        int droppedNoise = 0;
        int droppedFurniture = 0;
        int droppedChars = 0;
        int garbledBlocks = 0;

        // 第一遍：规范化 + 噪声/页眉页脚剔除（乱码只标记不删）
        for (MineruBlockParser.MineruBlock block : blocks) {
            if (block == null) continue;
            if (!AgentKnowledgeBlock.TEXT.equals(block.type())) {
                normalized.add(block);
                continue;
            }
            textBlocks++;
            String original = block.text() == null ? "" : block.text();
            charsBefore += original.length();
            String text = TextNormalizer.normalize(original);

            if (text.isBlank() || NoiseDetector.isDroppableNoise(text)) {
                CleaningAction action = NoiseDetector.isPageNumber(text)
                        ? CleaningAction.DROPPED_PAGE_NUMBER : CleaningAction.DROPPED_NOISE;
                records.put(block.blockId(), record(block, original, text, true, List.of(action), false));
                droppedNoise++;
                droppedChars += original.length();
                continue;
            }
            if (furniture.contains(block.blockId())) {
                records.put(block.blockId(), record(block, original, text, true,
                        List.of(CleaningAction.DROPPED_FURNITURE), false));
                droppedFurniture++;
                droppedChars += original.length();
                continue;
            }
            boolean garbled = NoiseDetector.isGarbled(text);
            if (garbled) garbledBlocks++;
            List<CleaningAction> actions = new ArrayList<>();
            if (!text.equals(original)) actions.add(CleaningAction.NORMALIZED);
            if (garbled) actions.add(CleaningAction.GARBLED_REVIEW);
            records.put(block.blockId(), record(block, original, text, false, actions, garbled));
            normalized.add(text.equals(original) ? block : withText(block, text));
        }

        // 第二遍：同页段落合并（跨页禁止，保护引用 bbox）
        List<MineruBlockParser.MineruBlock> merged = new ArrayList<>();
        int mergedParagraphs = 0;
        for (MineruBlockParser.MineruBlock block : normalized) {
            if (!merged.isEmpty() && paragraphMerger.canMerge(merged.getLast(), block)) {
                MineruBlockParser.MineruBlock previous = merged.removeLast();
                MineruBlockParser.MineruBlock combined = paragraphMerger.merge(previous, block);
                merged.add(combined);
                mergedParagraphs++;
                CleanedBlock previousRecord = records.get(previous.blockId());
                List<CleaningAction> actions = new ArrayList<>(previousRecord == null ? List.of() : previousRecord.actions());
                actions.add(CleaningAction.MERGED_PARAGRAPH);
                records.put(previous.blockId(), new CleanedBlock(previous.blockId(), previous.pageNumber(),
                        previousRecord == null ? previous.text() : previousRecord.originalText(),
                        combined.text(), false, actions, previousRecord != null && previousRecord.reviewRequired()));
                records.put(block.blockId(), record(block, block.text(), combined.text(), true,
                        List.of(CleaningAction.MERGED_PARAGRAPH), false));
            } else {
                merged.add(block);
            }
        }

        // 第三遍：完全重复去除（仅长文本；表格与短块不参与，避免误删重复条款）
        List<MineruBlockParser.MineruBlock> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (MineruBlockParser.MineruBlock block : merged) {
            if (settings.dedupeEnabled() && AgentKnowledgeBlock.TEXT.equals(block.type()) && !block.heading()) {
                String text = block.text() == null ? "" : block.text();
                if (text.length() >= settings.dedupeMinChars() && !seen.add(sha256(text))) {
                    CleanedBlock existing = records.get(block.blockId());
                    records.put(block.blockId(), new CleanedBlock(block.blockId(), block.pageNumber(),
                            existing == null ? text : existing.originalText(), text, true,
                            List.of(CleaningAction.DROPPED_DUPLICATE),
                            existing != null && existing.reviewRequired()));
                    duplicates++;
                    continue;
                }
            }
            deduped.add(block);
        }

        int charsAfter = deduped.stream()
                .filter(block -> AgentKnowledgeBlock.TEXT.equals(block.type()))
                .mapToInt(block -> block.text() == null ? 0 : block.text().length())
                .sum();
        // 回滚比例只统计“噪声/页眉页脚”删除量：完全重复块被去掉不减少信息量，
        // 不该因为文档本身重复段落多就把整篇清洗结果回滚。
        double deletionRatio = charsBefore == 0 ? 0.0d : (double) droppedChars / charsBefore;
        boolean rollback = charsBefore >= MIN_ROLLBACK_CHARS && deletionRatio > settings.maxDeletionRatio();
        BlockCleaningStats stats = new BlockCleaningStats(CleaningSettings.CLEANING_VERSION, blocks.size(),
                textBlocks, charsBefore, charsAfter, droppedNoise, droppedFurniture, duplicates,
                mergedParagraphs, garbledBlocks, Math.max(0.0d, deletionRatio), rollback);
        if (rollback) {
            log.warn("文档清洗删除比例(噪声/页眉页脚)超限，已整体回滚为原始块；before={}, dropped={}, ratio={}, limit={}",
                    charsBefore, droppedChars, String.format("%.3f", deletionRatio), settings.maxDeletionRatio());
            return new BlockCleaningResult(blocks, Map.of(), stats);
        }
        log.info("文档清洗完成：blocks={}, textBlocks={}, chars {}→{}, noise={}, furniture={}, dup={}, merged={}, garbled={}",
                blocks.size(), textBlocks, charsBefore, charsAfter, droppedNoise, droppedFurniture,
                duplicates, mergedParagraphs, garbledBlocks);
        return new BlockCleaningResult(deduped, records, stats);
    }

    private CleanedBlock record(MineruBlockParser.MineruBlock block, String original, String cleaned,
                                boolean dropped, List<CleaningAction> actions, boolean reviewRequired) {
        return new CleanedBlock(block.blockId(), block.pageNumber(), original, cleaned, dropped, actions, reviewRequired);
    }

    private MineruBlockParser.MineruBlock withText(MineruBlockParser.MineruBlock block, String text) {
        return new MineruBlockParser.MineruBlock(block.blockId(), block.type(), block.pageNumber(), block.bbox(),
                text, block.caption(), block.html(), block.table(), block.imagePath(), block.heading(),
                block.headingLevel(), block.rawJson());
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
