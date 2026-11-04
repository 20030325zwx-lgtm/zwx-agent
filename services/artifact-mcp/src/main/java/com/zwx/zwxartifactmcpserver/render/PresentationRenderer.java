package com.zwx.zwxartifactmcpserver.render;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 结构化幻灯片 → pptx（16:9）。不使用母版占位符，直接布文本框：
 * 从空演示文稿创建时母版可能为空，显式排版更稳定、样式也可控。
 */
@Service
public class PresentationRenderer {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    public record Slide(String title, List<String> bullets, String notes) {
    }

    public byte[] render(String deckTitle, List<Slide> slides) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(WIDTH, HEIGHT));

            if (deckTitle != null && !deckTitle.isBlank()) {
                XSLFSlide cover = ppt.createSlide();
                addText(cover, deckTitle, new Rectangle(90, 260, 1100, 120), 44, true, Color.decode("#1F2328"));
                String subtitle = slides != null && !slides.isEmpty() ? slides.get(0).title() : null;
                if (subtitle != null && !subtitle.isBlank()) {
                    addText(cover, subtitle, new Rectangle(90, 390, 1100, 80), 20, false, Color.decode("#5E626B"));
                }
            }

            if (slides != null) {
                for (Slide spec : slides) {
                    XSLFSlide slide = ppt.createSlide();
                    addText(slide, spec.title() == null ? "" : spec.title(),
                            new Rectangle(70, 50, 1140, 90), 28, true, Color.decode("#1F2328"));
                    StringBuilder bullets = new StringBuilder();
                    if (spec.bullets() != null) {
                        for (String bullet : spec.bullets()) {
                            if (bullets.length() > 0) bullets.append('\n');
                            bullets.append("• ").append(bullet == null ? "" : bullet);
                        }
                    }
                    if (bullets.length() > 0) {
                        addText(slide, bullets.toString(), new Rectangle(70, 160, 1140, 500), 18, false, Color.decode("#33363D"));
                    }
                }
            }
            ppt.write(out);
            return out.toByteArray();
        }
    }

    private void addText(XSLFSlide slide, String text, Rectangle box, int fontSize, boolean bold, Color color) {
        XSLFTextBox shape = slide.createTextBox();
        shape.setAnchor(box);
        shape.clearText();
        // clearText() 会清空段落列表，需要重新建段
        XSLFTextParagraph paragraph = shape.getTextParagraphs().isEmpty()
                ? shape.addNewTextParagraph()
                : shape.getTextParagraphs().get(0);
        paragraph.setLineSpacing(120d);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i += 1) {
            XSLFTextParagraph target = i == 0 ? paragraph : shape.addNewTextParagraph();
            XSLFTextRun run = target.addNewTextRun();
            run.setText(lines[i]);
            run.setFontSize((double) fontSize);
            run.setBold(bold);
            run.setFontColor(color);
        }
    }
}
