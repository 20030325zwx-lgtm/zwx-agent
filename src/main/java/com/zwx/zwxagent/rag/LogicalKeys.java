package com.zwx.zwxagent.rag;

/**
 * 逻辑文档标识归一化：文件名兜底策略——小写、去最后一个扩展名（换格式视为同一逻辑文档）、
 * 压缩非字母数字字符为连字符，空结果回退为 "document"。
 */
public final class LogicalKeys {

    private LogicalKeys() {
    }

    public static String normalize(String filename) {
        String base = filename == null ? "" : filename.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.toLowerCase();
        if (base.lastIndexOf('.') > 0) base = base.substring(0, base.lastIndexOf('.'));
        base = base.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        return base.isEmpty() ? "document" : base;
    }
}
