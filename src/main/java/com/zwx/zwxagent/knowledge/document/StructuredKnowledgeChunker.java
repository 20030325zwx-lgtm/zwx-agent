package com.zwx.zwxagent.knowledge.document;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按来源块生成知识切片：表格按行组切分，文本按章节聚合，图片保留图注。
 * 切片文本用于检索，metadata 中的 blockId/page/row 范围用于引用定位。
 */
@Component
public class StructuredKnowledgeChunker {

    static final int TEXT_MAX_CHARS = 1200;
    /**
     * 表格按行切片：引用需要定位到具体行，不能把多行合并后再高亮整段。
     * 每行重复表名和表头，保证单行切片仍可独立回答。
     */
    static final int TABLE_ROWS_PER_CHUNK = 1;

    public List<Document> chunks(List<MineruBlockParser.MineruBlock> blocks, Map<String, Object> baseMetadata) {
        return chunks(blocks, baseMetadata, FigureSupport.NONE);
    }

    /** 带图纸上下文的完整切分：figures 含逻辑图分组与视觉增强描述（NONE = 增强不可用，按图注降级）。 */
    public List<Document> chunks(List<MineruBlockParser.MineruBlock> blocks, Map<String, Object> baseMetadata,
                                 FigureSupport figures) {
        List<Document> chunks = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) return chunks;
        List<String> headings = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Integer textPage = null;
        String textBlockId = null;
        String textBbox = null;

        for (MineruBlockParser.MineruBlock block : blocks) {
            if (block == null) continue;
            if (block.heading()) {
                flushText(chunks, baseMetadata, headings, text, textPage, textBlockId, textBbox);
                textPage = null;
                textBlockId = null;
                textBbox = null;
                updateHeadings(headings, block);
                continue;
            }
            if (AgentKnowledgeBlock.TABLE.equals(block.type())) {
                flushText(chunks, baseMetadata, headings, text, textPage, textBlockId, textBbox);
                textPage = null;
                textBlockId = null;
                textBbox = null;
                addTableChunks(chunks, baseMetadata, headings, block);
                continue;
            }
            if (AgentKnowledgeBlock.FIGURE.equals(block.type())) {
                flushText(chunks, baseMetadata, headings, text, textPage, textBlockId, textBbox);
                textPage = null;
                textBlockId = null;
                textBbox = null;
                // 同一逻辑图的成员块只在组头处理一次，其余成员跳过
                FigureGrouper.FigureGroup group = figures.groupOf(block);
                if (group == null) {
                    addFigureChunks(chunks, baseMetadata, headings, List.of(block), figures.descriptionOf(block));
                } else if (group.blocks().getFirst() == block) {
                    addFigureChunks(chunks, baseMetadata, headings, group.blocks(), figures.descriptions().get(group.id()));
                }
                continue;
            }
            String value = block.text();
            if (value == null || value.isBlank()) continue;
            if (textPage != null && block.pageNumber() != null && !textPage.equals(block.pageNumber())) {
                flushText(chunks, baseMetadata, headings, text, textPage, textBlockId, textBbox);
                textBlockId = null;
                textBbox = null;
            }
            if (textPage == null) textPage = block.pageNumber();
            if (textBlockId == null) textBlockId = block.blockId();
            if (textBbox == null) textBbox = block.bbox();
            if (text.length() > 0 && text.length() + value.length() + 2 > TEXT_MAX_CHARS) {
                flushText(chunks, baseMetadata, headings, text, textPage, textBlockId, textBbox);
                textPage = block.pageNumber();
                textBlockId = block.blockId();
                textBbox = block.bbox();
            }
            if (text.length() > 0) text.append("\n\n");
            text.append(value.trim());
        }
        flushText(chunks, baseMetadata, headings, text, textPage, textBlockId, textBbox);
        return chunks;
    }

    private void addTableChunks(List<Document> chunks, Map<String, Object> baseMetadata, List<String> headings,
                                MineruBlockParser.MineruBlock block) {
        HtmlTableParser.TableData table = block.table();
        if (table == null || table.isEmpty()) {
            String fallback = firstNonBlank(block.caption(), block.text());
            if (fallback != null) {
                Map<String, Object> metadata = baseMetadata(chunks, baseMetadata, headings, block);
                metadata.put("sourceType", AgentKnowledgeBlock.TABLE);
                metadata.put("chunking", "structured-block");
                metadata.put("structuredContentType", "table-record");
                chunks.add(Document.builder().text(withSection(headings, "[表格] " + fallback)).metadata(metadata).build());
            }
            return;
        }
        for (int rowStart = 0; rowStart < table.rows().size(); rowStart += TABLE_ROWS_PER_CHUNK) {
            int rowEnd = Math.min(rowStart + TABLE_ROWS_PER_CHUNK, table.rows().size());
            StringBuilder content = new StringBuilder();
            String section = sectionPath(headings);
            if (!section.isBlank()) content.append("[章节] ").append(section).append("\n\n");
            content.append("[表格] ").append(firstNonBlank(table.caption(), "第 " + pageLabel(block.pageNumber()) + " 页表格")).append('\n');
            if (!table.headerLine().isBlank()) content.append("列：").append(table.headerLine()).append('\n');
            String records = table.recordBlock(rowStart, rowEnd);
            if (!records.isBlank()) content.append(records);

            Map<String, Object> metadata = baseMetadata(chunks, baseMetadata, headings, block);
            metadata.put("sourceType", AgentKnowledgeBlock.TABLE);
            metadata.put("chunking", "structured-block");
            metadata.put("structuredContentType", "table-record");
            metadata.put("rowStart", rowStart);
            metadata.put("rowEnd", rowEnd);
            metadata.put("tableCaption", table.caption() == null ? "" : table.caption());
            metadata.put("regionLabel", regionLabel(table.caption(), block.pageNumber(), rowStart, rowEnd));
            chunks.add(Document.builder().text(content.toString().trim()).metadata(metadata).build());
        }
    }

    /**
     * 图纸 chunk：优先使用视觉增强描述（一图一组 chunk：1 描述 + N 假设问题），
     * 描述不可用时降级为仅图注；图注与描述皆无则不产出（无任何可检索语义）。
     */
    private void addFigureChunks(List<Document> chunks, Map<String, Object> baseMetadata, List<String> headings,
                                 List<MineruBlockParser.MineruBlock> members, FigureDescription description) {
        MineruBlockParser.MineruBlock representative = members.getFirst();
        String caption = members.stream().map(MineruBlockParser.MineruBlock::caption)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        Map<String, Object> metadata = baseMetadata(chunks, baseMetadata, headings, representative);
        if (members.size() > 1) metadata.put("figureFragments", members.size());
        String images = members.stream().map(MineruBlockParser.MineruBlock::imagePath)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "|" + right).orElse("");
        if (!images.isBlank()) metadata.put("figureImages", images);
        metadata.put("sourceType", AgentKnowledgeBlock.FIGURE);
        metadata.put("chunking", "structured-block");
        metadata.put("regionLabel", figureLabel(caption, representative.pageNumber()));
        if (description == null || !description.hasContent()) {
            if (caption.isBlank()) return;
            metadata.put("chunkRole", "figure_caption");
            metadata.put("figureCaption", caption);
            chunks.add(Document.builder().text(withSection(headings, "[图片] " + caption)).metadata(metadata).build());
            return;
        }
        metadata.put("chunkRole", "figure_description");
        metadata.put("figureCaption", caption);
        if (!description.type().isBlank()) metadata.put("figureType", description.type());
        metadata.put("figureTruncated", description.truncated());
        StringBuilder text = new StringBuilder("[图纸");
        if (!description.type().isBlank()) text.append("|").append(description.type());
        text.append("] ");
        if (!caption.isBlank()) text.append(caption).append('\n');
        if (!description.summary().isBlank()) text.append("摘要：").append(description.summary()).append('\n');
        if (!description.nodes().isEmpty()) {
            text.append("组成要素：").append(String.join("、", description.nodes())).append('\n');
        }
        if (!description.flows().isEmpty()) {
            text.append("流向：").append(String.join("；", description.flows())).append('\n');
        }
        if (!description.visibleText().isEmpty()) {
            text.append("图内文字：").append(String.join("、", description.visibleText())).append('\n');
        }
        if (!description.inferred().isEmpty()) {
            text.append("推断（未核实）：").append(String.join("；", description.inferred())).append('\n');
        }
        chunks.add(Document.builder().text(withSection(headings, text.toString().trim())).metadata(metadata).build());
        // 假设问题单独成 chunk：用户以自然语言提问时命中问题向量（上限 4，成本约束）
        for (String question : description.questions().stream().limit(4).toList()) {
            Map<String, Object> questionMetadata = new HashMap<>(metadata);
            questionMetadata.put("chunkRole", "figure_question");
            chunks.add(Document.builder().text(withSection(headings, question)).metadata(questionMetadata).build());
        }
    }

    private void flushText(List<Document> chunks, Map<String, Object> baseMetadata, List<String> headings,
                           StringBuilder text, Integer pageNumber, String blockId, String bbox) {
        String value = text.toString().trim();
        text.setLength(0);
        if (value.isBlank()) return;
        Map<String, Object> metadata = new HashMap<>(baseMetadata);
        String section = sectionPath(headings);
        if (!section.isBlank()) metadata.put("sectionPath", section);
        if (pageNumber != null) metadata.put("pageNumber", pageNumber);
        if (blockId != null) metadata.put("blockId", blockId);
        if (bbox != null) metadata.put("bbox", bbox);
        metadata.put("sourceType", AgentKnowledgeBlock.TEXT);
        metadata.put("chunking", "structured-block");
        metadata.put("chunkSchemaVersion", 2);
        chunks.add(Document.builder().text(withSection(headings, value)).metadata(metadata).build());
    }

    private Map<String, Object> baseMetadata(List<Document> chunks, Map<String, Object> baseMetadata,
                                             List<String> headings, MineruBlockParser.MineruBlock block) {
        Map<String, Object> metadata = new HashMap<>(baseMetadata);
        String section = sectionPath(headings);
        if (!section.isBlank()) metadata.put("sectionPath", section);
        if (block.pageNumber() != null) metadata.put("pageNumber", block.pageNumber());
        if (block.blockId() != null) metadata.put("blockId", block.blockId());
        if (block.bbox() != null) metadata.put("bbox", block.bbox());
        if (block.caption() != null && !block.caption().isBlank()) metadata.put("caption", block.caption());
        metadata.put("chunkSchemaVersion", 2);
        return metadata;
    }

    private void updateHeadings(List<String> headings, MineruBlockParser.MineruBlock block) {
        String value = block.text() == null ? "" : block.text().trim();
        if (value.isBlank()) return;
        int level = Math.min(6, Math.max(1, block.headingLevel() > 0 ? block.headingLevel() : 1));
        while (headings.size() < level) headings.add("");
        headings.set(level - 1, value);
        while (headings.size() > level) headings.remove(headings.size() - 1);
    }

    private String withSection(List<String> headings, String content) {
        String section = sectionPath(headings);
        return section.isBlank() ? content : "[章节] " + section + "\n\n" + content;
    }

    private String sectionPath(List<String> headings) {
        return headings.stream().filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " > " + right).orElse("");
    }

    private String regionLabel(String caption, Integer pageNumber, int rowStart, int rowEnd) {
        List<String> parts = new ArrayList<>();
        if (caption != null && !caption.isBlank()) parts.add(caption);
        if (pageNumber != null) parts.add("第 " + pageNumber + " 页");
        parts.add(rowStart + 1 == rowEnd
                ? "第 " + (rowStart + 1) + " 行"
                : "第 " + (rowStart + 1) + "-" + rowEnd + " 行");
        return String.join(" · ", parts);
    }

    private String figureLabel(String caption, Integer pageNumber) {
        List<String> parts = new ArrayList<>();
        if (caption != null && !caption.isBlank()) parts.add(caption);
        if (pageNumber != null) parts.add("第 " + pageNumber + " 页");
        return String.join(" · ", parts);
    }

    private String pageLabel(Integer pageNumber) {
        return pageNumber == null ? "未知" : String.valueOf(pageNumber);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
