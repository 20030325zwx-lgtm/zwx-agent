package com.zwx.zwxartifactmcpserver.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * artifact-mcp 骨架工具：只回答「这个服务现在能做什么、计划做什么、产物契约是什么」。
 *
 * <p>P1 阶段交付物生成仍在 backend 内置工具里（writeFile / generatePDF / generateSpreadsheet），
 * 本服务先以可运行的 MCP 骨架存在，P2 再接管 docx / pptx / 格式转换等重格式能力。</p>
 */
@Service
public class ArtifactTool {

    @Tool(description = """
            Report what this artifact service can generate right now and what is planned.
            Use it to confirm availability before promising document formats.""")
    public String artifactServiceInfo(
            @ToolParam(description = "Optional format you are interested in, e.g. docx, pptx, xlsx, pdf", required = false) String format) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "aster-artifact-mcp");
        body.put("stage", "skeleton");
        body.put("readyFormats", List.of());
        body.put("plannedFormats", List.of(
                Map.of("format", "docx", "status", "planned", "note", "P2：模板驱动的 Word 文档生成"),
                Map.of("format", "pptx", "status", "planned", "note", "P2：模板驱动的演示文稿"),
                Map.of("format", "pdf", "status", "planned", "note", "P2：长文档 / Markdown 转 PDF（含目录、页眉页脚）"),
                Map.of("format", "convert", "status", "planned", "note", "P2：docx→pdf 等格式转换")
        ));
        body.put("availableNow", List.of(
                Map.of("tool", "generateSpreadsheet", "where", "backend", "note", "P1 内置：xlsx 表格"),
                Map.of("tool", "generatePDF", "where", "backend", "note", "P1 内置：PDF"),
                Map.of("tool", "writeFile", "where", "backend", "note", "P1 内置：md/txt/csv/json/html 等文本交付物")
        ));
        body.put("contract", Map.of(
                "outputRoot", "/artifacts",
                "returns", "相对路径 + mime + 字节数 + 页数/行数",
                "binaryOverMcp", false,
                "limits", Map.of("maxBytes", 50 * 1024 * 1024, "timeoutSeconds", 120)
        ));
        if (format != null && !format.isBlank()) {
            String normalized = format.trim().toLowerCase();
            body.put("requestedFormat", normalized);
            body.put("requestedFormatReady", false);
        }
        return toJson(body);
    }

    private String toJson(Map<String, Object> body) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(entry.getKey()).append("\":").append(jsonValue(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append('"').append(entry.getKey()).append("\":").append(jsonValue(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i += 1) {
                if (i > 0) out.append(',');
                out.append(jsonValue(list.get(i)));
            }
            return out.append(']').toString();
        }
        return '"' + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
