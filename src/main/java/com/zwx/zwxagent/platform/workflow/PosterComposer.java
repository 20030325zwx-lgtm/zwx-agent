package com.zwx.zwxagent.platform.workflow;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 海报合成：纯 Java2D，无外部渲染依赖。版式为固定模板——渐变背景 + 白色商品图面板 +
 * 标题/副标题/三条卖点 + 品牌页脚；文案与配色由文案规划步骤生成。
 */
final class PosterComposer {

    record PosterCopy(String title, String subtitle, List<String> points,
                      String bgFrom, String bgTo, String accent, String text) {
    }

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 1600;

    private PosterComposer() {
    }

    static byte[] compose(byte[] productImage, PosterCopy copy) {
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        Color accent = safeColor(copy.accent(), "#2E6BE6");
        Color text = safeColor(copy.text(), "#16224A");
        Color bgFrom = safeColor(copy.bgFrom(), "#EAF1FF");
        Color bgTo = safeColor(copy.bgTo(), "#D6E6FF");

        g.setPaint(new GradientPaint(0, 0, bgFrom, 0, HEIGHT, bgTo));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(accent);
        g.fillRect(0, HEIGHT - 96, WIDTH, 96);

        // 商品图面板
        int panelSize = 860;
        int panelX = (WIDTH - panelSize) / 2;
        int panelY = 140;
        g.setColor(Color.WHITE);
        g.fillRoundRect(panelX, panelY, panelSize, panelSize, 48, 48);
        g.setColor(new Color(0, 0, 0, 18));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(panelX, panelY, panelSize, panelSize, 48, 48);
        try {
            BufferedImage product = ImageIO.read(new ByteArrayInputStream(productImage));
            if (product != null) {
                int inset = 44;
                int box = panelSize - inset * 2;
                double scale = Math.min(box / (double) product.getWidth(), box / (double) product.getHeight());
                int w = Math.max(1, (int) Math.round(product.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(product.getHeight() * scale));
                g.drawImage(product, panelX + (panelSize - w) / 2, panelY + (panelSize - h) / 2, w, h, null);
            }
        } catch (IOException exception) {
            // 商品图解码失败时保留空白面板，海报其余部分照常输出
        }

        Font titleFont = cjkFont(Font.BOLD, 96);
        Font subFont = cjkFont(Font.PLAIN, 44);
        Font pointFont = cjkFont(Font.PLAIN, 38);
        Font footFont = cjkFont(Font.PLAIN, 30);

        int textTop = panelY + panelSize + 92;
        g.setColor(text);
        g.setFont(titleFont);
        drawCentered(g, copy.title(), WIDTH / 2, textTop, titleFont);
        textTop += 118;

        g.setColor(new Color(text.getRed(), text.getGreen(), text.getBlue(), 200));
        g.setFont(subFont);
        drawCentered(g, copy.subtitle(), WIDTH / 2, textTop, subFont);
        textTop += 96;

        g.setFont(pointFont);
        int bulletY = textTop;
        for (String point : copy.points()) {
            g.setColor(accent);
            g.fillOval(180, bulletY - 24, 18, 18);
            g.setColor(text);
            g.setFont(pointFont);
            g.drawString(truncate(point, 14), 220, bulletY);
            bulletY += 62;
        }

        g.setColor(Color.WHITE);
        g.setFont(footFont);
        drawCentered(g, "Aster 工作流生成", WIDTH / 2, HEIGHT - 38, footFont);

        g.dispose();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("海报编码失败", exception);
        }
    }

    private static Font cjkFont(int style, int size) {
        for (String candidate : List.of("PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC")) {
            Font font = new Font(candidate, style, size);
            if (!font.getFamily().equals("Dialog")) return font;
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private static void drawCentered(Graphics2D g, String text, int centerX, int baselineY, Font font) {
        if (text == null || text.isBlank()) return;
        FontMetrics metrics = g.getFontMetrics(font);
        g.setFont(font);
        g.drawString(truncate(text, 14), centerX - metrics.stringWidth(truncate(text, 14)) / 2, baselineY);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    private static Color safeColor(String value, String fallback) {
        try {
            return Color.decode(value == null || value.isBlank() ? fallback : value.trim());
        } catch (NumberFormatException exception) {
            return Color.decode(fallback);
        }
    }
}
