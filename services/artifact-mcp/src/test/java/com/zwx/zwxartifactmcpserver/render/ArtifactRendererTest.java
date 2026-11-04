package com.zwx.zwxartifactmcpserver.render;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 排版引擎最小回归：docx / pptx / pdf / 模板填充 / LibreOffice 转换。 */
class ArtifactRendererTest {

    private final DocumentRenderer documentRenderer = new DocumentRenderer();
    private final PresentationRenderer presentationRenderer = new PresentationRenderer();
    private final PdfRenderer pdfRenderer = new PdfRenderer("");
    private final ConversionService conversionService = new ConversionService("", 120);

    @Test
    void docxRendersHeadingsTableAndBold() throws Exception {
        String markdown = """
                # 季度复盘
                ## 收入
                本季度 **收入** 同比增长 18%。
                - 新客户 12 家
                - 续约率 92%

                | 城市 | 销售额 |
                | --- | --- |
                | 北京 | 120 |
                | 上海 | 90 |
                """;
        byte[] bytes = documentRenderer.render("2026 Q3 报告", markdown);
        assertThat(bytes.length).isGreaterThan(1000);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream()
                    .map(p -> p.getText()).reduce("", (a, b) -> a + b);
            assertThat(text).contains("2026 Q3 报告").contains("季度复盘").contains("收入")
                    .contains("本季度 收入 同比增长 18%");
            assertThat(document.getTables()).hasSize(1);
            assertThat(document.getTables().get(0).getNumberOfRows()).isEqualTo(3);
        }
    }

    @Test
    void docxTemplateFillReplacesPlaceholders() throws Exception {
        byte[] template = documentRenderer.render(null, "合同编号：{{contractNo}}\n甲方：{{party}}");
        byte[] filled = documentRenderer.fillTemplate(template, Map.of("contractNo", "A-2026-001", "party", "星枢科技"));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(filled))) {
            String text = document.getParagraphs().stream().map(p -> p.getText()).reduce("", (a, b) -> a + b);
            assertThat(text).contains("合同编号：A-2026-001").contains("甲方：星枢科技");
        }
    }

    @Test
    void pptxRendersCoverAndContentSlides() throws Exception {
        byte[] bytes = presentationRenderer.render("产品介绍", List.of(
                new PresentationRenderer.Slide("背景", List.of("市场增长", "客户反馈"), null),
                new PresentationRenderer.Slide("方案", List.of("一站式创作"), "备注：强调效率")));
        assertThat(bytes.length).isGreaterThan(1000);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(show.getSlides()).hasSize(3);
            StringBuilder all = new StringBuilder();
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : show.getSlides()) {
                for (org.apache.poi.sl.usermodel.Shape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape) {
                        all.append(textShape.getText());
                    }
                }
            }
            assertThat(all.toString()).contains("产品介绍").contains("背景").contains("市场增长");
        }
    }

    @Test
    void pdfRendersWhenCjkFontPresent() throws Exception {
        assumeTrue(pdfRenderer.resolveFontPath() != null, "no CJK font on this machine");
        byte[] bytes = pdfRenderer.render("测试文档", "# 标题\n正文内容 **加粗**\n- 要点一");
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void convertsDocxToPdfWhenSofficePresent() throws Exception {
        assumeTrue(conversionService.resolveSoffice() != null, "LibreOffice not installed");
        byte[] docx = documentRenderer.render("转换测试", "# 标题\n正文");
        byte[] pdf = conversionService.toPdf("convert-test.docx", docx);
        assertThat(pdf.length).isGreaterThan(500);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Path leftover = Files.list(Path.of(System.getProperty("java.io.tmpdir")))
                .filter(path -> path.getFileName().toString().startsWith("artifact-convert"))
                .findFirst().orElse(null);
        assertThat(leftover == null || !Files.isDirectory(leftover) || Files.list(leftover).findAny().isEmpty()).isTrue();
    }
}
