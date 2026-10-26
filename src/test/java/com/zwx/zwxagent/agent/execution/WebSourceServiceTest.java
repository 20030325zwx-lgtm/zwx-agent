package com.zwx.zwxagent.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;


import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSourceServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final WebSourceService service = new WebSourceService(jdbcTemplate);
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void collectsTavilySearchResultsAndScrapesWithDeduplication() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String tavily = List.of(
                mapper.writeValueAsString(Map.of("title", "新疆旅游攻略", "url", "https://example.com/a", "content", "最佳季节是九月")),
                mapper.writeValueAsString(Map.of("title", "喀纳斯", "link", "https://example.com/b", "content", "湖水九月最蓝"))
        ).stream().reduce((a, b) -> a + "," + b).orElseThrow();
        when(jdbcTemplate.queryForList(anyString(), eq("run-1"))).thenReturn(List.of(
                Map.of("tool_name", "searchWeb", "arguments", "{}", "result", tavily, "created_at", Timestamp.from(now)),
                Map.of("tool_name", "scrapeWebPage", "arguments", "{\"url\":\"https://example.com/a\"}",
                        "result", "页面正文", "created_at", Timestamp.from(now)),
                Map.of("tool_name", "scrapeWebPage", "arguments", "{\"url\":\"https://example.com/c\"}",
                        "result", "页面正文2", "created_at", Timestamp.from(now))
        ));

        List<WebSourceService.WebSource> sources = service.sourcesForRun("run-1");

        Assertions.assertEquals(3, sources.size());
        Assertions.assertEquals("https://example.com/a", sources.get(0).url());
        Assertions.assertEquals("新疆旅游攻略", sources.get(0).title());
        Assertions.assertEquals("search", sources.get(0).kind());
        Assertions.assertEquals("最佳季节是九月", sources.get(0).snippet());
        Assertions.assertEquals("https://example.com/b", sources.get(1).url());
        // 同一 URL 先搜索后抓取：保留首次（搜索带标题）记录
        Assertions.assertEquals("search", sources.stream().filter(s -> s.url().endsWith("/a")).findFirst().orElseThrow().kind());
        Assertions.assertEquals("example.com", sources.get(2).title());
        Assertions.assertEquals("scrape", sources.get(2).kind());
    }

    @Test
    void skipsUnavailableAndMalformedResults() {
        when(jdbcTemplate.queryForList(anyString(), eq("run-2"))).thenReturn(List.of(
                Map.of("tool_name", "searchWeb", "arguments", "{}",
                        "result", "SEARCH_UNAVAILABLE: Tavily returned no results", "created_at", Timestamp.from(now)),
                Map.of("tool_name", "searchWeb", "arguments", "{}", "result", "not-json{", "created_at", Timestamp.from(now))
        ));
        Assertions.assertTrue(service.sourcesForRun("run-2").isEmpty());
    }

    @Test
    void emptyRunIdReturnsEmpty() {
        Assertions.assertTrue(service.sourcesForRun("").isEmpty());
        Assertions.assertTrue(service.sourcesForRun(null).isEmpty());
    }

    @Test
    void dbFailureDegradesToEmpty() {
        when(jdbcTemplate.queryForList(anyString(), eq("run-3"))).thenThrow(new IllegalStateException("db down"));
        Assertions.assertTrue(service.sourcesForRun("run-3").isEmpty());
    }
}
