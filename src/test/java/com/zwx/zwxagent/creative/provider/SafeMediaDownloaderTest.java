package com.zwx.zwxagent.creative.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A21（C07）：产物下载的 SSRF 与体积限制——回环/私网拒绝、逐跳校验、元数据地址拒绝、
 * 超限中断、正常下载。纯 JUnit + 本地 HttpServer。
 */
class SafeMediaDownloaderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicLong servedBytes = new AtomicLong();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "PNGDATA".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/big", exchange -> {
            // 先声明大 Content-Length，再分批写：下载器必须中途放弃
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[16 * 1024];
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 64; i++) {
                    out.write(chunk);
                    servedBytes.addAndGet(chunk.length);
                }
            }
        });
        server.createContext("/missing", exchange -> respond(exchange, 404, new byte[0]));
        server.createContext("/redirect-private", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws java.io.IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void blocksLoopbackWhenLocalNotAllowed() {
        var downloader = new SafeMediaDownloader(false, 1024);
        var error = Assertions.assertThrows(IllegalStateException.class,
                () -> downloader.download(baseUrl + "/ok"));
        Assertions.assertTrue(error.getMessage().contains("内网/回环"), error.getMessage());
    }

    @Test
    void allowsLoopbackOnlyWhenExplicitlyEnabled() {
        var downloader = new SafeMediaDownloader(true, 1024);
        Assertions.assertArrayEquals("PNGDATA".getBytes(StandardCharsets.UTF_8),
                downloader.download(baseUrl + "/ok"));
    }

    @Test
    void rejectsNonHttpScheme() {
        var downloader = new SafeMediaDownloader(true, 1024);
        Assertions.assertThrows(IllegalStateException.class,
                () -> downloader.download("file:///etc/passwd"));
    }

    @Test
    void rejectsRedirectToMetadataAddress() {
        var downloader = new SafeMediaDownloader(true, 1024);
        var error = Assertions.assertThrows(IllegalStateException.class,
                () -> downloader.download(baseUrl + "/redirect-private"));
        Assertions.assertTrue(error.getMessage().contains("元数据"), error.getMessage());
    }

    @Test
    void abortsOversizeDownloadMidStream() {
        var downloader = new SafeMediaDownloader(true, 32 * 1024);
        var error = Assertions.assertThrows(IllegalStateException.class,
                () -> downloader.download(baseUrl + "/big"));
        Assertions.assertTrue(error.getMessage().contains("大小限制"), error.getMessage());
        Assertions.assertTrue(servedBytes.get() < 16 * 1024L * 64, "应在流式读取中提前中止");
    }

    @Test
    void rejectsHttpErrorStatus() {
        var downloader = new SafeMediaDownloader(true, 1024);
        var error = Assertions.assertThrows(IllegalStateException.class,
                () -> downloader.download(baseUrl + "/missing"));
        Assertions.assertTrue(error.getMessage().contains("HTTP 404"), error.getMessage());
    }
}
