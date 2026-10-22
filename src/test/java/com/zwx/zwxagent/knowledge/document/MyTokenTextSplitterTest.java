package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyTokenTextSplitterTest {
    @Test
    void keepsMarkdownSectionsAndAddsSectionPath() {
        String markdown = """
                ## 工作要求

                ### 1、应用服务器需求测算

                - 采用TPC-C经验公式进行测算
                - 给出测算依据
                - 给出参数说明

                ### 4、网络需求测算

                - 用户访问流量
                - 应用服务器与数据库服务器交互流量
                - 数据采集与共享交换流量
                - 大数据集群内部通信流量
                - 峰值带宽需求汇总

                最终得出应用服务器网络带宽需求，并给出万兆网络的测算依据。
                """;
        List<Document> chunks = new MyTokenTextSplitter().splitStructuredMarkdown(List.of(Document.builder().text(markdown).build()));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> "structured-markdown".equals(chunk.getMetadata().get("chunking"))));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getText().contains("应用服务器需求测算")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getText().contains("网络需求测算") && chunk.getText().contains("峰值带宽需求汇总")));
        assertFalse(chunks.stream().anyMatch(chunk -> chunk.getText().endsWith("并给出")));
    }

    @Test
    void canInspectExternalMarkdownFixtureWhenRequested() throws Exception {
        String fixture = System.getProperty("structuredMarkdownFixture");
        assumeTrue(fixture != null && !fixture.isBlank());
        String markdown = java.nio.file.Files.readString(java.nio.file.Path.of(fixture));
        List<Document> chunks = new MyTokenTextSplitter().splitStructuredMarkdown(List.of(Document.builder().text(markdown).build()));
        System.out.println("structured markdown chunks=" + chunks.size());
        chunks.forEach(chunk -> System.out.println("---\n" + chunk.getText()));
    }

    @Test
    void preservesPdfPageProvenanceForCitations() {
        String extractedPdf = """
                [PDF 第 3 页]
                ## 发放规则
                奖金在三月发放。
                """;
        List<Document> chunks = new MyTokenTextSplitter().splitStructuredMarkdown(List.of(Document.builder().text(extractedPdf).build()));
        assertTrue(chunks.stream().allMatch(chunk -> Integer.valueOf(3).equals(chunk.getMetadata().get("pageNumber"))));
    }

    @Test
    void expandsHtmlTableRowspansIntoHeaderLabeledRecords() {
        String html = """
                ## 待办功能流程测试
                <table>
                  <thead>
                    <tr><th rowspan="2">流程</th><th colspan="2">检查项</th></tr>
                    <tr><th>测试动作</th><th>检查内容</th></tr>
                  </thead>
                  <tbody>
                    <tr><td rowspan="2">待我审批</td><td>审批详情</td><td>检查待办中心详情</td></tr>
                    <tr><td>待办处理</td><td>检查审批后是否正常转为已办</td></tr>
                  </tbody>
                </table>
                """;

        List<Document> chunks = new MyTokenTextSplitter().splitStructuredMarkdown(
                List.of(Document.builder().text(html).build()));
        String text = chunks.stream().map(Document::getText).reduce("", (left, right) -> left + "\n" + right);

        assertEquals(2, occurrences(text, "流程：待我审批"));
        assertTrue(text.contains("检查项 / 测试动作：审批详情；检查项 / 检查内容：检查待办中心详情"));
        assertTrue(text.contains("检查项 / 测试动作：待办处理；检查项 / 检查内容：检查审批后是否正常转为已办"));
        assertFalse(text.contains("<table"));
        assertTrue(chunks.stream().allMatch(chunk -> "table-record".equals(chunk.getMetadata().get("structuredContentType"))));
    }

    @Test
    void keepsLongHtmlTableRowsWholeAcrossChunks() {
        String longCheck = "检查待办流程状态与审批结果是否一致。".repeat(45);
        String html = """
                ## 流程验收
                <table>
                  <tr><th>用例</th><th>检查内容</th></tr>
                  <tr><td>案例一</td><td>%s</td></tr>
                  <tr><td>案例二</td><td>%s</td></tr>
                  <tr><td>案例三</td><td>%s</td></tr>
                </table>
                """.formatted(longCheck, longCheck, longCheck);

        List<Document> chunks = new MyTokenTextSplitter().splitStructuredMarkdown(
                List.of(Document.builder().text(html).build()));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getText().contains("检查内容：")));
        for (String example : List.of("案例一", "案例二", "案例三")) {
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.getText().contains(example)
                    && chunk.getText().contains(longCheck)));
        }
    }

    private int occurrences(String text, String value) {
        return text.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
