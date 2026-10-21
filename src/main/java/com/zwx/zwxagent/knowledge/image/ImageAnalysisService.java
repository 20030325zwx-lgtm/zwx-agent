package com.zwx.zwxagent.knowledge.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.capability.mcp.McpServerConfigurationService;
import com.zwx.zwxagent.capability.mcp.McpTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ImageAnalysisService {

    private final McpServerConfigurationService mcpService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String describeTool;

    public ImageAnalysisService(McpServerConfigurationService mcpService,
                                ObjectMapper objectMapper,
                                @Value("${app.mcp.image-tools.enabled:true}") boolean enabled,
                                @Value("${app.mcp.image-tools.describe-tool:describe_image}") String describeTool) {
        this.mcpService = mcpService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.describeTool = describeTool;
    }

    public ImageAnalysisResult analyze(String tenantId, String imageUrl, String question) {
        if (!enabled) return ImageAnalysisResult.unavailable("图片 MCP 工具未启用");
        try (McpTools tools = mcpService.toolsFor(tenantId)) {
            ToolCallback callback = null;
            for (ToolCallback candidate : tools.callbacks()) {
                String name = candidate.getToolDefinition().name();
                if (name.equals(describeTool) || name.endsWith("_" + describeTool)) {
                    callback = candidate;
                    log.info("Image MCP tool selected; name={}", name);
                    break;
                }
            }
            if (callback == null) return ImageAnalysisResult.unavailable("未配置 " + describeTool + " MCP 工具");
            String arguments = objectMapper.writeValueAsString(Map.of(
                    "image_url", imageUrl,
                    "question", question == null ? "" : question));
            String result = callback.call(arguments);
            return parse(result);
        } catch (Exception exception) {
            log.warn("Image MCP analysis failed; tool={}, reason={}", describeTool, exception.getMessage());
            return ImageAnalysisResult.unavailable("图片分析服务不可用");
        }
    }

    private ImageAnalysisResult parse(String result) {
        if (result == null || result.isBlank()) return ImageAnalysisResult.unavailable("图片分析返回为空");
        try {
            JsonNode node = objectMapper.readTree(result);
            // MCP callbacks wrap tool output as {"text":"..."}; unwrap nested JSON strings.
            for (int depth = 0; depth < 5; depth++) {
                if (node.isArray() && !node.isEmpty()) {
                    node = node.get(0);
                    continue;
                }
                if (node.isObject() && node.has("text") && node.get("text").isTextual()) {
                    node = objectMapper.readTree(node.get("text").asText());
                    continue;
                }
                if (node.isTextual()) {
                    node = objectMapper.readTree(node.asText());
                    continue;
                }
                break;
            }
            if (node.hasNonNull("error")) {
                return ImageAnalysisResult.unavailable(truncate(node.path("error").asText(), 200));
            }
            List<String> uncertain = new ArrayList<>();
            if (node.path("uncertain_items").isArray()) {
                node.path("uncertain_items").forEach(item -> {
                    if (!item.asText().isBlank()) uncertain.add(truncate(item.asText(), 120));
                });
            }
            ImageAnalysisResult parsed = new ImageAnalysisResult(
                    truncate(node.path("summary").asText(""), 200),
                    truncate(node.path("extracted_text").asText(""), 4000),
                    uncertain.stream().limit(3).toList(),
                    truncate(node.path("retrieval_query").asText(""), 180),
                    true, "");
            if (parsed.summary().isBlank() && parsed.extractedText().isBlank() && parsed.retrievalQuery().isBlank()) {
                return ImageAnalysisResult.unavailable("图片分析未返回有效内容");
            }
            log.info("Image analysis parsed; summaryChars={}, textChars={}, queryChars={}",
                    parsed.summary().length(), parsed.extractedText().length(), parsed.retrievalQuery().length());
            return parsed;
        } catch (Exception exception) {
            return new ImageAnalysisResult(truncate(result, 200), "", List.of(), "", true, "");
        }
    }

    private String truncate(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
