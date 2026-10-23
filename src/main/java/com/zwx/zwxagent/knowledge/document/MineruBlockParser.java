package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 MinerU 的 content_list.json 转成统一来源块。
 * 解析保持宽松：不同 MinerU 版本/后端的字段名不一致时，优先回退到 text/caption。
 */
@Component
public class MineruBlockParser {

    public record MineruBlock(String blockId,
                              String type,
                              Integer pageNumber,
                              String bbox,
                              String text,
                              String caption,
                              String html,
                              HtmlTableParser.TableData table,
                              String imagePath,
                              boolean heading,
                              int headingLevel,
                              String rawJson) {

        public boolean hasContent() {
            return (text != null && !text.isBlank())
                    || (table != null && !table.isEmpty())
                    || (caption != null && !caption.isBlank());
        }
    }

    private final ObjectMapper objectMapper;

    public MineruBlockParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<MineruBlock> parse(MineruParseResult result) {
        if (result == null) return List.of();
        Optional<Map.Entry<String, byte[]>> contentList = findContentList(result.files());
        if (contentList.isEmpty()) return List.of();
        JsonNode root;
        try {
            root = objectMapper.readTree(contentList.get().getValue());
        } catch (Exception exception) {
            return List.of();
        }
        List<PageBlocks> pages = extractPages(root);
        List<MineruBlock> blocks = new ArrayList<>();
        int index = 0;
        for (PageBlocks page : pages) {
            for (JsonNode node : page.blocks()) {
                Integer pageNumber = page.pageNumber() != null ? page.pageNumber() : readPageNumber(node);
                MineruBlock block = readBlock(node, index++, pageNumber);
                if (block != null && block.hasContent()) blocks.add(block);
            }
        }
        return blocks;
    }

    private Optional<Map.Entry<String, byte[]>> findContentList(Map<String, byte[]> files) {
        if (files == null || files.isEmpty()) return Optional.empty();
        List<String> names = files.keySet().stream()
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("content_list"))
                .sorted(Comparator
                        .comparing((String name) -> !name.equalsIgnoreCase("content_list.json"))
                        .thenComparing(name -> name.length())
                        .thenComparing(name -> name))
                .toList();
        return names.stream().findFirst().map(name -> Map.entry(name, files.get(name)));
    }

    private List<PageBlocks> extractPages(JsonNode root) {
        if (root == null) return List.of();
        if (root.isArray()) return List.of(new PageBlocks(null, toList(root)));
        JsonNode contentList = root.path("content_list");
        if (contentList.isArray()) return List.of(new PageBlocks(null, toList(contentList)));
        JsonNode blocks = root.path("blocks");
        if (blocks.isArray()) return List.of(new PageBlocks(null, toList(blocks)));
        JsonNode pages = root.path("pages");
        if (pages.isArray()) {
            List<PageBlocks> result = new ArrayList<>();
            for (JsonNode page : pages) {
                JsonNode pageBlocks = page.path("blocks");
                if (!pageBlocks.isArray()) pageBlocks = page.path("content_list");
                if (!pageBlocks.isArray()) pageBlocks = page.path("items");
                Integer pageNumber = readPageNumber(page);
                if (pageBlocks.isArray()) {
                    result.add(new PageBlocks(pageNumber, toList(pageBlocks)));
                }
            }
            return result;
        }
        JsonNode found = findBlockArray(root);
        return found == null ? List.of() : List.of(new PageBlocks(null, toList(found)));
    }

    private JsonNode findBlockArray(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (looksLikeBlock(item)) return node;
            }
            return null;
        }
        if (!node.isObject()) return null;
        for (var fields = node.fields(); fields.hasNext(); ) {
            JsonNode found = findBlockArray(fields.next().getValue());
            if (found != null) return found;
        }
        return null;
    }

    private boolean looksLikeBlock(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        if (node.hasNonNull("type")) {
            return node.has("text") || node.has("table_body") || node.has("img_path") || node.has("image_path");
        }
        return false;
    }

    private List<JsonNode> toList(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    private MineruBlock readBlock(JsonNode node, int index, Integer pageNumber) {
        if (node == null || !node.isObject()) return null;
        String type = node.path("type").asText("text").toLowerCase(java.util.Locale.ROOT);
        String blockId = node.path("id").asText("");
        if (blockId.isBlank()) blockId = "p" + (pageNumber == null ? "x" : pageNumber) + "-b" + index;
        String bbox = node.has("bbox") ? node.get("bbox").toString() : null;
        String rawJson = node.toString();
        return switch (type) {
            case "table" -> tableBlock(node, blockId, pageNumber, bbox, rawJson);
            case "image", "figure" -> imageBlock(node, blockId, pageNumber, bbox, rawJson);
            case "equation", "formula" -> new MineruBlock(blockId, AgentKnowledgeBlock.EQUATION, pageNumber, bbox,
                    node.path("text").asText(""), caption(node, "caption"), null, null, null, false, 0, rawJson);
            case "header", "footer", "page_number" -> null;
            default -> textBlock(node, blockId, pageNumber, bbox, rawJson);
        };
    }

    private MineruBlock textBlock(JsonNode node, String blockId, Integer pageNumber, String bbox, String rawJson) {
        String text = node.path("text").asText("");
        if (text.isBlank()) text = node.path("content").asText("");
        int level = node.path("text_level").asInt(0);
        return new MineruBlock(blockId, AgentKnowledgeBlock.TEXT, pageNumber, bbox, text, null, null, null, null,
                level > 0, level, rawJson);
    }

    private MineruBlock tableBlock(JsonNode node, String blockId, Integer pageNumber, String bbox, String rawJson) {
        String html = node.path("table_body").asText("");
        if (html.isBlank()) html = node.path("html").asText("");
        String caption = joinCaption(caption(node, "table_caption"), caption(node, "table_footnote"));
        HtmlTableParser.TableData table = HtmlTableParser.parse(html, caption);
        if (table.isEmpty()) table = HtmlTableParser.parseMarkdown(html, caption);
        if (table == null) table = new HtmlTableParser.TableData(caption, java.util.List.of(), java.util.List.of());
        if (table.isEmpty()) {
            String textBlock = node.path("text").asText("");
            if (!textBlock.isBlank()) {
                HtmlTableParser.TableData markdown = HtmlTableParser.parseMarkdown(textBlock, caption);
                if (markdown != null) table = markdown;
            }
        }
        String text = table.isEmpty() ? node.path("text").asText("") : "";
        return new MineruBlock(blockId, AgentKnowledgeBlock.TABLE, pageNumber, bbox, text, caption, html, table,
                null, false, 0, rawJson);
    }

    private MineruBlock imageBlock(JsonNode node, String blockId, Integer pageNumber, String bbox, String rawJson) {
        String path = node.path("img_path").asText("");
        if (path.isBlank()) path = node.path("image_path").asText("");
        String caption = joinCaption(caption(node, "img_caption"), caption(node, "img_footnote"));
        if (caption.isBlank()) caption = caption(node, "image_caption");
        return new MineruBlock(blockId, AgentKnowledgeBlock.FIGURE, pageNumber, bbox, "", caption, null, null, path,
                false, 0, rawJson);
    }

    private Integer readPageNumber(JsonNode node) {
        if (node == null) return null;
        for (String key : List.of("page_idx", "page_index")) {
            if (node.has(key) && node.get(key).isNumber()) {
                int value = node.get(key).asInt();
                return value >= 0 ? value + 1 : null;
            }
        }
        for (String key : List.of("page", "page_number", "pageNo")) {
            if (node.has(key) && node.get(key).isNumber()) {
                int value = node.get(key).asInt();
                return value > 0 ? value : null;
            }
        }
        return null;
    }

    private String caption(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isBlank()) parts.add(text);
            });
            return String.join(" ", parts);
        }
        return value.asText("").trim();
    }

    private String joinCaption(String caption, String footnote) {
        List<String> parts = new ArrayList<>();
        if (caption != null && !caption.isBlank()) parts.add(caption);
        if (footnote != null && !footnote.isBlank()) parts.add(footnote);
        return String.join(" ", parts);
    }

    private record PageBlocks(Integer pageNumber, List<JsonNode> blocks) {
    }
}
