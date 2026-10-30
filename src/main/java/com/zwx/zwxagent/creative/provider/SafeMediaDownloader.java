package com.zwx.zwxagent.creative.provider;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 产物下载器（C07）：逐跳校验 URL（scheme、DNS 解析结果拒绝回环/私网/链路本地/元数据地址）、
 * 限制重定向跳数与下载字节数（A21）。允许本机地址仅限桌面/测试场景（配置开关）。
 */
public class SafeMediaDownloader {

    private final boolean allowLocal;
    private final long maxBytes;
    private final HttpClient httpClient;

    public SafeMediaDownloader(boolean allowLocal, long maxBytes) {
        this.allowLocal = allowLocal;
        this.maxBytes = maxBytes;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public byte[] download(String url) {
        URI current = URI.create(url);
        for (int hop = 0; hop < 4; hop++) {
            validate(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Aster-Creative/0.1")
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> response;
            try {
                // 流式读取：超限立即中断，避免把超大响应整体读入内存
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception exception) {
                throw new IllegalStateException("产物下载失败：" + exception.getMessage(), exception);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new IllegalStateException("重定向缺少 Location");
                }
                current = current.resolve(location);
                continue;
            }
            if (status != 200) {
                throw new IllegalStateException("产物下载失败：HTTP " + status);
            }
            byte[] body = readLimited(response.body(), maxBytes);
            if (body.length == 0) {
                throw new IllegalStateException("产物为空");
            }
            return body;
        }
        throw new IllegalStateException("重定向次数过多");
    }

    private static byte[] readLimited(java.io.InputStream stream, long limit) {
        try (stream) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IllegalStateException("产物超过大小限制 " + limit);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("产物下载中断：" + exception.getMessage(), exception);
        }
    }

    private void validate(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalStateException("产物 URL 仅支持 HTTP(S)：" + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("产物 URL 缺少主机");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                // 云元数据地址任何模式下都不允许
                if (address.getHostAddress().startsWith("169.254.")) {
                    throw new IllegalStateException("产物 URL 指向元数据地址，已拦截");
                }
                if (allowLocal) continue;
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                    throw new IllegalStateException("产物 URL 指向内网/回环地址，已拦截");
                }
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalStateException("产物 URL 主机不可解析：" + host, exception);
        }
    }
}
