package com.zwx.zwxagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class ResourceDownloadToolTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectPrivateNetworkUrl() {
        ResourceDownloadTool tool = new ResourceDownloadTool(new UrlAccessPolicy(), new ToolSandbox(), tempDir, 1024);
        String result = tool.downloadResource("http://169.254.169.254/latest/meta-data/", "meta.txt");
        Assertions.assertTrue(result.startsWith("Error downloading resource:"));
        Assertions.assertTrue(result.contains("private network"));
    }

    @Test
    void rejectPathTraversalFileName() {
        ResourceDownloadTool tool = new ResourceDownloadTool(new UrlAccessPolicy(), new ToolSandbox(), tempDir, 1024);
        String result = tool.downloadResource("https://example.com/logo.png", "../../escape.png");
        Assertions.assertTrue(result.startsWith("Error downloading resource:"));
    }

    @Test
    void rejectUnresolvableHost() {
        ResourceDownloadTool tool = new ResourceDownloadTool(new UrlAccessPolicy(), new ToolSandbox(), tempDir, 1024);
        String result = tool.downloadResource("https://this-host-does-not-exist-zwx.invalid/logo.png", "logo.png");
        Assertions.assertTrue(result.startsWith("Error downloading resource:"));
    }
}
