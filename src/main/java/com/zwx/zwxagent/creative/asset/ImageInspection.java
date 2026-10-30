package com.zwx.zwxagent.creative.asset;

/**
 * 图片嗅探结果：真实类型（按文件签名判定）与尺寸（PNG/JPEG/WebP-VP8X 可解析，其余 null）。
 */
public record ImageInspection(Type type, Integer width, Integer height) {

    public enum Type { PNG, JPEG, WEBP, OTHER }

    public boolean supported() {
        return type != Type.OTHER;
    }

    public boolean withinPixelBudget() {
        return width == null || height == null || (long) width * height <= 40_000_000L;
    }
}
