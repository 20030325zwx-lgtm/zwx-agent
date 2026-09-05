package com.zwx.zwxagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class WebScrapingToolTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectPrivateNetworkUrl() {
        WebScrapingTool tool = new WebScrapingTool(new UrlAccessPolicy());
        String result = tool.scrapeWebPage("http://127.0.0.1:5432/");
        Assertions.assertTrue(result.startsWith("Error scraping web page:"));
        Assertions.assertTrue(result.contains("private network"));
    }

    @Test
    void rejectNonHttpScheme() {
        WebScrapingTool tool = new WebScrapingTool(new UrlAccessPolicy());
        String result = tool.scrapeWebPage("file:///etc/passwd");
        Assertions.assertTrue(result.startsWith("Error scraping web page:"));
    }
}
