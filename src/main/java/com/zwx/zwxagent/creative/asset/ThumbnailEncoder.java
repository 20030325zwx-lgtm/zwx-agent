package com.zwx.zwxagent.creative.asset;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 缩略图生成（C11/A17）：只做展示用降采样，失败返回 null，绝不影响原件入库。
 * 统一输出 PNG，最长边 320px，避免把原始大图直接塞进列表。
 */
public final class ThumbnailEncoder {

    public static final int MAX_EDGE = 320;

    private ThumbnailEncoder() {
    }

    public static byte[] encode(byte[] original) {
        if (original == null || original.length == 0) return null;
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return null;
            double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(target, "png", out)) return null;
            return out.toByteArray();
        } catch (Exception exception) {
            return null;
        }
    }

    /** 缩略图对象键：与原图同目录，固定后缀，避免暴露绝对路径。 */
    public static String thumbnailKey(String objectKey) {
        return objectKey + ".thumb.png";
    }
}
