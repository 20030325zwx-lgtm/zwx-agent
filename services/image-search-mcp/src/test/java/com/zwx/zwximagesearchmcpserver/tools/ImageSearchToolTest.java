package com.zwx.zwximagesearchmcpserver.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSearchToolTest {

    private final ImageSearchTool tool = new ImageSearchTool("test-key");

    @Test
    void parsesMediumImageUrls() {
        String body = """
                {"photos":[{"src":{"medium":"https://images.pexels.com/one.jpg"}},
                           {"src":{"medium":""}},{"src":{}}]}
                """;
        assertEquals(List.of("https://images.pexels.com/one.jpg"), tool.parseImages(200, body));
    }

    @Test
    void explainsUpstreamFailuresInsteadOfThrowingNullPointerException() {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> tool.parseImages(401, "{\"error\":\"Unauthorized\"}"))
                .getMessage().contains("API key"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> tool.parseImages(429, "{}"))
                .getMessage().contains("rate limit"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> tool.parseImages(200, "{\"error\":\"unexpected\"}"))
                .getMessage().contains("photos array"));
    }

    @Test
    void reportsMissingConfigurationBeforeMakingRequest() {
        assertEquals("Error search image: PEXELS_API_KEY is not configured",
                new ImageSearchTool("").searchImage("bird"));
    }
}
