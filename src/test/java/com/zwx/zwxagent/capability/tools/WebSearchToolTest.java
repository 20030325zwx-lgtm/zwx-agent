package com.zwx.zwxagent.capability.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WebSearchToolTest {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Value("${search-api.provider:searchapi}")
    private String searchProvider;

    @Test
    void searchWeb() {
        WebSearchTool webSearchTool = new WebSearchTool(searchProvider, searchApiKey);
        String query = "Java 编程项目实践指南";
        String result = webSearchTool.searchWeb(query);
        Assertions.assertNotNull(result);
    }
}
