package com.zwx.zwxagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
}
