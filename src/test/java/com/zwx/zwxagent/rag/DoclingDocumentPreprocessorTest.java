package com.zwx.zwxagent.rag;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class DoclingDocumentPreprocessorTest {

    @Test
    void extractsFurnitureButSkipsPageNumbersAndBodyDuplicates() {
        Map<String, Object> json = Map.of("texts", List.of(
                Map.of("label", "page_header", "content_layer", "furniture", "text", "17768576135"),
                Map.of("label", "page_header", "content_layer", "furniture", "text", "1796866501@qq.com"),
                Map.of("label", "page_footer", "content_layer", "furniture", "text", "第 1 页"),
                Map.of("label", "page_footer", "content_layer", "furniture", "text", "1"),
                Map.of("label", "text", "content_layer", "body", "text", "Java 后端开发")
        ));
        DoclingChunkResponse response = new DoclingChunkResponse(List.of(), 1.0,
                List.of(new DoclingDocumentResult(Map.of("json_content", json))));

        PreprocessedDocument result = new DoclingDocumentPreprocessor(10, 1000)
                .preprocess(response, "朱文轩-简历.pdf", List.of());

        Assertions.assertTrue(result.resume());
        Assertions.assertEquals(List.of("17768576135", "1796866501@qq.com"), result.furnitureText());
    }

    @Test
    void skipsFurnitureAlreadyPresentInBodyChunks() {
        Map<String, Object> json = Map.of("texts", List.of(
                Map.of("label", "page_header", "content_layer", "furniture", "text", "1796866501@qq.com")
        ));
        DoclingChunkResponse response = new DoclingChunkResponse(List.of(), 1.0,
                List.of(new DoclingDocumentResult(Map.of("json_content", json))));
        DoclingChunk body = new DoclingChunk(0, "联系方式 1796866501@qq.com", null, 10,
                null, null, null, null, null);

        PreprocessedDocument result = new DoclingDocumentPreprocessor(10, 1000)
                .preprocess(response, "resume.pdf", List.of(body));

        Assertions.assertTrue(result.furnitureText().isEmpty());
    }

    @Test
    void detectsResumeByChineseOrEnglishFilename() {
        Assertions.assertTrue(DoclingDocumentPreprocessor.isResume("朱文轩-简历.pdf"));
        Assertions.assertTrue(DoclingDocumentPreprocessor.isResume("Jane-CV.docx"));
        Assertions.assertFalse(DoclingDocumentPreprocessor.isResume("guide.pdf"));
    }
}
