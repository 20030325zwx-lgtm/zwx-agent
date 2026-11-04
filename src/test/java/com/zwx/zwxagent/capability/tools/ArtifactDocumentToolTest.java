package com.zwx.zwxagent.capability.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** artifact 工具：引擎调用、落盘与错误分支（用 JDK HttpServer 打桩，不依赖真实服务）。 */
class ArtifactDocumentToolTest {

    private final ToolSandbox sandbox = new ToolSandbox();

    private Path workDir;

    private HttpServer stub(int status, byte[] body, String[] capturedPath) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (capturedPath != null) capturedPath[0] = exchange.getRequestURI().getPath();
            byte[] response = body == null ? new byte[0] : body;
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private ArtifactDocumentTool toolWith(HttpServer server) {
        int port = server.getAddress().getPort();
        return new ArtifactDocumentTool(sandbox, workDir,
                new ArtifactClient("http://127.0.0.1:" + port, "secret", 30, 5 * 1024 * 1024));
    }

    @Test
    void createDocumentWritesEngineBytesIntoWorkspace() throws Exception {
        workDir = Files.createTempDirectory("artifact-tool-test");
        String[] path = new String[1];
        HttpServer server = stub(200, "DOCX-BYTES".getBytes(StandardCharsets.UTF_8), path);
        try {
            String result = toolWith(server).createDocument("reports/q3.docx", "Q3 报告", "# 标题\n正文");
            assertThat(result).contains("\"file\":\"reports/q3.docx\"").contains("\"bytes\":10");
            assertThat(path[0]).isEqualTo("/internal/render/docx");
            Path file = workDir.resolve("reports/q3.docx");
            assertThat(Files.exists(file)).isTrue();
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo("DOCX-BYTES");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createPresentationValidatesSlidesJson() throws Exception {
        workDir = Files.createTempDirectory("artifact-tool-test");
        HttpServer server = stub(200, new byte[]{1, 2, 3}, null);
        try {
            ArtifactDocumentTool tool = toolWith(server);
            assertThat(tool.createPresentation("deck.pptx", "标题", "not-json")).contains("slides is not valid JSON");
            assertThat(tool.createPresentation("deck.pptx", "标题", "[]")).contains("non-empty");
            assertThat(tool.createPresentation("deck.docx", "标题", "[{\"title\":\"a\"}]")).contains("must end with .pptx");
            assertThat(tool.createPresentation("deck.pptx", "标题",
                    "[{\"title\":\"背景\",\"bullets\":[\"要点\"]}]")).contains("\"bytes\":3");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void engineErrorSurfaceAsReadableMessage() throws Exception {
        workDir = Files.createTempDirectory("artifact-tool-test");
        HttpServer server = stub(500, "no usable CJK font".getBytes(StandardCharsets.UTF_8), null);
        try {
            String result = toolWith(server).createPdfFromMarkdown("doc.pdf", "标题", "正文");
            assertThat(result).contains("Error: artifact generation failed").contains("500");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unconfiguredEngineIsRejectedBeforeWriting() throws Exception {
        workDir = Files.createTempDirectory("artifact-tool-test");
        ArtifactDocumentTool tool = new ArtifactDocumentTool(sandbox, workDir,
                new ArtifactClient("", "", 30, 1024 * 1024));
        assertThat(tool.createDocument("a.docx", "", "x")).contains("artifact service is not configured");
        assertThat(Files.exists(workDir.resolve("a.docx"))).isFalse();
    }
}
