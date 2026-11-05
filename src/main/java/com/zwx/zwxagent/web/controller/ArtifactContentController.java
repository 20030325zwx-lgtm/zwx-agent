package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.platform.security.ArtifactSignatureService;
import com.zwx.zwxagent.platform.storage.ObjectStorage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 工作流产物同源下载（2026-11-05）：对象存储切内网 MinIO 后，&lt;img&gt;/&lt;a&gt; 不能再直连预签名地址。
 * 令牌由 {@link ArtifactSignatureService} 签发，绑定对象键与归属，短时效；仅本端点可用。
 */
@RestController
@RequestMapping("/ai/workflows/artifacts")
public class ArtifactContentController {

    private final ObjectStorage storage;
    private final ArtifactSignatureService signatures;

    public ArtifactContentController(ObjectStorage storage, ArtifactSignatureService signatures) {
        this.storage = storage;
        this.signatures = signatures;
    }

    @GetMapping("/content")
    public void content(@RequestParam("key") String key,
                        @RequestParam("artifact") String token,
                        @RequestParam(value = "download", required = false) String downloadName,
                        HttpServletRequest request,
                        HttpServletResponse response) throws IOException {
        var access = signatures.verify(key, token)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "产物链接无效或已过期"));
        // 令牌已绑定对象键；再校验归属前缀，防止把本人令牌挪用到他人运行目录
        if (!key.startsWith("workflow/" + access.userId() + "/") || key.contains("..")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权访问该产物");
        }
        long size;
        try {
            size = storage.size(key);
        } catch (RuntimeException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "产物不存在或已被清理");
        }
        String mimeType = mimeTypeOf(key);
        String disposition = disposition(downloadName, fileNameOf(key));
        String range = request.getHeader("Range");
        if (range != null && !range.isBlank() && mimeType.startsWith("video/")) {
            writeRange(key, size, mimeType, disposition, range, response);
            return;
        }
        response.setStatus(200);
        response.setContentType(mimeType);
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentLengthLong(size);
        response.setHeader("Content-Disposition", disposition);
        response.setHeader("Cache-Control", "private, max-age=300");
        try (InputStream in = storage.open(key)) {
            in.transferTo(response.getOutputStream());
        }
    }

    /** bytes=start-end / bytes=start- / bytes=-suffix；非法区间返回 416（视频拖动依赖 206）。 */
    private void writeRange(String key, long size, String mimeType, String disposition, String range,
                            HttpServletResponse response) throws IOException {
        long start = 0;
        long end = size - 1;
        boolean partial = false;
        boolean invalid = false;
        String normalized = range.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("bytes=")) {
            String spec = normalized.substring(6);
            int dash = spec.indexOf('-');
            String startText = dash > 0 ? spec.substring(0, dash) : "";
            String endText = dash >= 0 ? spec.substring(dash + 1) : "";
            try {
                if (startText.isBlank() && !endText.isBlank()) {
                    long suffix = Long.parseLong(endText);
                    if (suffix <= 0) {
                        invalid = true;
                    } else {
                        start = Math.max(0, size - suffix);
                        end = size - 1;
                        partial = true;
                    }
                } else if (!startText.isBlank()) {
                    start = Long.parseLong(startText);
                    end = endText.isBlank() ? size - 1 : Long.parseLong(endText);
                    if (end > size - 1) end = size - 1;
                    partial = true;
                }
            } catch (NumberFormatException exception) {
                invalid = true;
            }
        } else if (!normalized.isBlank()) {
            invalid = true;
        }
        if (invalid || size <= 0 || start < 0 || start > end || start >= size) {
            response.setStatus(416);
            response.setHeader("Content-Range", "bytes */" + Math.max(0, size));
            return;
        }
        long length = end - start + 1;
        response.setStatus(partial ? 206 : 200);
        response.setContentType(mimeType);
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentLengthLong(length);
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        response.setHeader("Content-Disposition", disposition);
        try (InputStream in = storage.open(key)) {
            long skipped = in.skip(start);
            while (skipped < start) {
                long step = in.skip(start - skipped);
                if (step <= 0) return;
                skipped += step;
            }
            byte[] buffer = new byte[16 * 1024];
            long remaining = length;
            var out = response.getOutputStream();
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static String disposition(String downloadName, String fallbackName) {
        String name = downloadName == null || downloadName.isBlank() ? fallbackName : downloadName;
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return (downloadName == null || downloadName.isBlank() ? "inline" : "attachment")
                + "; filename*=UTF-8''" + encoded;
    }

    private static String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 && slash < key.length() - 1 ? key.substring(slash + 1) : "artifact";
    }

    private static String mimeTypeOf(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".md") || lower.endsWith(".txt")) return "text/plain; charset=UTF-8";
        return "application/octet-stream";
    }
}
