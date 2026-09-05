package com.zwx.zwxagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ResourceDownloadTool {

    private static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private final UrlAccessPolicy urlAccessPolicy;
    private final ToolSandbox sandbox;
    private final Path workDir;
    private final Path downloadDir;
    private final long maxBytes;

    public ResourceDownloadTool(UrlAccessPolicy urlAccessPolicy, ToolSandbox sandbox, Path workDir, long maxBytes) {
        this.urlAccessPolicy = urlAccessPolicy;
        this.sandbox = sandbox;
        this.workDir = workDir;
        this.downloadDir = workDir.resolve("download");
        this.maxBytes = maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
    }

    @Tool(description = "Download a resource from a public HTTP(S) URL into the current task workspace")
    public String downloadResource(@ToolParam(description = "Public URL of the resource to download") String url,
                                   @ToolParam(description = "Name of the file to save the downloaded resource, relative to the task workspace") String fileName) {
        try {
            Path target = sandbox.resolveWithin(downloadDir, fileName);
            Files.createDirectories(target.getParent());
            long bytes = download(urlAccessPolicy.validateHttpUrl(url), target, 0);
            return "Resource downloaded successfully to: " + workDir.relativize(target) + " (" + bytes + " bytes)";
        } catch (IllegalArgumentException | IOException exception) {
            return "Error downloading resource: " + exception.getMessage();
        }
    }

    private long download(URI uri, Path target, int redirectDepth) throws IOException {
        if (redirectDepth > MAX_REDIRECTS) {
            throw new IOException("Too many redirects");
        }
        urlAccessPolicy.validateHttpUrl(uri.toString());
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Redirect without Location header");
                return download(urlAccessPolicy.validateHttpUrl(location), target, redirectDepth + 1);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP status " + status);
            }
            long total = 0;
            try (InputStream inputStream = connection.getInputStream();
                 java.io.OutputStream output = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("Download exceeds the " + (maxBytes / (1024 * 1024)) + " MB limit");
                    }
                    output.write(buffer, 0, read);
                }
            }
            return total;
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
            }
            throw exception;
        } finally {
            connection.disconnect();
        }
    }
}
