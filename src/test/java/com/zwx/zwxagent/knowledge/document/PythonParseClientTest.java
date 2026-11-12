package com.zwx.zwxagent.knowledge.document;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 统一解析服务客户端契约测试：POST /parse 返回 zip → 复用 MinerU 解包/块解析管线。
 * 用 JDK 内置 HttpServer 做 stub，不引入 WireMock 等新依赖。
 */
class PythonParseClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int responseStatus = 200;
    private volatile byte[] responseBody = new byte[0];

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/parse", exchange -> {
            requests.incrementAndGet();
            try (InputStream input = exchange.getRequestBody()) {
                input.readAllBytes();
            }
            exchange.sendResponseHeaders(responseStatus, responseBody.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesMineruCompatibleZipIntoBlocks() {
        responseBody = sampleZip();
        PythonParseClient client = new PythonParseClient(true, baseUrl, 10, 1024 * 1024);

        Assertions.assertTrue(client.isConfigured());
        Assertions.assertTrue(client.supports("合同.pdf"));
        Assertions.assertFalse(client.supports("说明.md"));

        MineruParseResult result = client.parse("hello".getBytes(StandardCharsets.UTF_8), "合同.pdf");
        Assertions.assertEquals(1, requests.get());
        Assertions.assertTrue(result.markdown().contains("付款条款"));
        Assertions.assertTrue(result.fileNames().contains("content_list.json"));

        List<MineruBlockParser.MineruBlock> blocks =
                new MineruBlockParser(new com.fasterxml.jackson.databind.ObjectMapper()).parse(result);
        Assertions.assertEquals(3, blocks.size());

        MineruBlockParser.MineruBlock text = blocks.get(0);
        Assertions.assertEquals(AgentKnowledgeBlock.TEXT, text.type());
        Assertions.assertEquals("第三章 付款条款", text.text());
        Assertions.assertTrue(text.heading());
        Assertions.assertEquals(1, text.pageNumber());

        MineruBlockParser.MineruBlock figure = blocks.get(1);
        Assertions.assertEquals(AgentKnowledgeBlock.FIGURE, figure.type());
        Assertions.assertEquals("images/p1_0000.png", figure.imagePath());
        Assertions.assertEquals("图 1 审批流程", figure.caption());

        MineruBlockParser.MineruBlock table = blocks.get(2);
        Assertions.assertEquals(AgentKnowledgeBlock.TABLE, table.type());
        Assertions.assertEquals("表 1 付款节点", table.caption());
        Assertions.assertNotNull(table.table());
        Assertions.assertEquals(List.of("节点", "比例"), table.table().headers());
    }

    @Test
    void disabledClientIsNotConfiguredAndSupportsNothing() {
        PythonParseClient client = new PythonParseClient(false, baseUrl, 10, 1024 * 1024);
        Assertions.assertFalse(client.isConfigured());
        Assertions.assertFalse(client.supports("合同.pdf"));
        Assertions.assertThrows(PythonParseException.class,
                () -> client.parse("x".getBytes(StandardCharsets.UTF_8), "合同.pdf"));
    }

    @Test
    void httpFailureIsWrappedAsPythonParseException() {
        responseStatus = 500;
        responseBody = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
        PythonParseClient client = new PythonParseClient(true, baseUrl, 10, 1024 * 1024);
        Assertions.assertThrows(PythonParseException.class,
                () -> client.parse("x".getBytes(StandardCharsets.UTF_8), "合同.pdf"));
    }

    @Test
    void connectionFailureIsWrappedAsPythonParseException() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);
        PythonParseClient client = new PythonParseClient(true, "http://127.0.0.1:" + port, 2, 1024 * 1024);
        Assertions.assertThrows(PythonParseException.class,
                () -> client.parse("x".getBytes(StandardCharsets.UTF_8), "合同.pdf"));
    }

    private static byte[] sampleZip() {
        String contentList = """
                [
                  {"type":"text","text":"第三章 付款条款","page_idx":0,"bbox":[10,50,300,90],"text_level":1},
                  {"type":"image","img_path":"images/p1_0000.png","img_caption":["图 1 审批流程"],"image_footnote":[],"page_idx":0,"bbox":[10,100,300,300]},
                  {"type":"table","table_body":"<table><tr><th>节点</th><th>比例</th></tr><tr><td>首付</td><td>30%</td></tr></table>","table_caption":["表 1 付款节点"],"table_footnote":[],"page_idx":0,"bbox":[10,320,500,500]}
                ]
                """;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("full.md"));
            zip.write("# 合同\n\n付款条款正文".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content_list.json"));
            zip.write(contentList.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("images/p1_0000.png"));
            zip.write(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            zip.closeEntry();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return buffer.toByteArray();
    }
}
