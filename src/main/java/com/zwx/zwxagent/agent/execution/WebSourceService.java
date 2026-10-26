package com.zwx.zwxagent.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联网引用收集：从幂等账本读取本 run 的搜索/抓取调用，提取 URL、标题与抓取时间，
 * 让实时信息与知识库切片一样可溯源。只读账本，不在工具执行路径上增加任何开销。
 * 搜索结果兼容两种格式：SearchAPI（原始 organic 对象）与 Tavily（title/link/content），
 * 均为逗号拼接的 JSON 对象串，以「包裹成数组」方式解析；解析失败按无引用处理。
 */
@Slf4j
@Service
public class WebSourceService {

    private static final Set<String> SEARCH_TOOLS = Set.of("searchWeb", "webSearch");
    private static final Set<String> SCRAPE_TOOLS = Set.of("scrapeWebPage");
    private static final int MAX_SOURCES = 40;
    private static final int MAX_SNIPPET_CHARS = 160;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSourceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<WebSource> sourcesForRun(String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList("""
                    SELECT tool_name, arguments, result, created_at FROM agent_tool_call
                    WHERE run_id = ? AND status = 'SUCCEEDED'
                      AND tool_name IN ('searchWeb', 'webSearch', 'scrapeWebPage')
                    ORDER BY id
                    """, runId);
        } catch (Exception exception) {
            log.warn("读取联网引用失败；runId={}", runId, exception);
            return List.of();
        }
        Map<String, WebSource> sources = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String toolName = String.valueOf(row.get("tool_name"));
            Instant retrievedAt = timestamp(row.get("created_at"));
            if (SEARCH_TOOLS.contains(toolName)) {
                collectSearchResults(text(row.get("result")), retrievedAt, sources);
            } else if (SCRAPE_TOOLS.contains(toolName)) {
                collectScrape(text(row.get("arguments")), retrievedAt, sources);
            }
            if (sources.size() >= MAX_SOURCES) break;
        }
        return List.copyOf(sources.values());
    }

    private void collectSearchResults(String result, Instant retrievedAt, Map<String, WebSource> sources) {
        if (result == null || result.isBlank() || result.startsWith("SEARCH_UNAVAILABLE")) return;
        JsonNode array;
        try {
            array = objectMapper.readTree("[" + result + "]");
        } catch (Exception exception) {
            log.debug("搜索结果解析失败，按无引用处理：{}", exception.getMessage());
            return;
        }
        if (!array.isArray()) return;
        for (JsonNode node : array) {
            String url = node.path("link").asText(node.path("url").asText(""));
            if (url.isBlank()) continue;
            String title = node.path("title").asText("");
            String snippet = truncate(node.path("content").asText(node.path("snippet").asText("")));
            sources.putIfAbsent(url, new WebSource(url, title.isBlank() ? hostOf(url) : title,
                    "search", snippet, retrievedAt));
            if (sources.size() >= MAX_SOURCES) return;
        }
    }

    private void collectScrape(String arguments, Instant retrievedAt, Map<String, WebSource> sources) {
        if (arguments == null || arguments.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(arguments);
            String url = node.path("url").asText("");
            if (url.isBlank()) return;
            sources.putIfAbsent(url, new WebSource(url, hostOf(url), "scrape", "", retrievedAt));
        } catch (Exception exception) {
            log.debug("抓取参数解析失败，按无引用处理：{}", exception.getMessage());
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost() == null ? url : URI.create(url).getHost();
        } catch (Exception exception) {
            return url;
        }
    }

    private static String truncate(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_SNIPPET_CHARS ? normalized : normalized.substring(0, MAX_SNIPPET_CHARS) + "…";
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant timestamp(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return null;
    }

    public record WebSource(String url, String title, String kind, String snippet, Instant retrievedAt) {
    }
}
