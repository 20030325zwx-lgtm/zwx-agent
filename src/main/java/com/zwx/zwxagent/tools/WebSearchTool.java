package com.zwx.zwxagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 网页搜索工具
 */
public class WebSearchTool {

    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";
    private static final String TAVILY_API_URL = "https://api.tavily.com/search";

    private final String provider;
    private final String apiKey;

    public WebSearchTool(String provider, String apiKey) {
        this.provider = provider == null ? "searchapi" : provider.trim().toLowerCase();
        this.apiKey = apiKey;
    }

    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query) {
        if (apiKey == null || apiKey.isBlank()) return "SEARCH_UNAVAILABLE: " + provider + " API key is not configured.";
        return switch (provider) {
            case "searchapi" -> searchWithSearchApi(query);
            case "tavily" -> searchWithTavily(query);
            default -> "SEARCH_UNAVAILABLE: Unsupported search provider '" + provider + "'. Supported providers: searchapi, tavily.";
        };
    }

    private static final int HTTP_TIMEOUT_MILLIS = 10_000;

    private String searchWithSearchApi(String query) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            String response = HttpRequest.get(SEARCH_API_URL)
                    .form(paramMap)
                    .setConnectionTimeout(HTTP_TIMEOUT_MILLIS)
                    .setReadTimeout(HTTP_TIMEOUT_MILLIS)
                    .execute()
                    .body();
            JSONObject jsonObject = JSONUtil.parseObj(response);
            String error = jsonObject.getStr("error");
            if (error == null || error.isBlank()) error = jsonObject.getStr("message");
            if (error != null && !error.isBlank()) return "SEARCH_UNAVAILABLE: SearchAPI returned an error: " + error;
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            if (organicResults == null || organicResults.isEmpty()) {
                return "SEARCH_UNAVAILABLE: SearchAPI returned no organic results for this query.";
            }
            List<Object> objects = organicResults.subList(0, Math.min(organicResults.size(), 5));
            String result = objects.stream().map(obj -> {
                JSONObject tmpJSONObject = (JSONObject) obj;
                return tmpJSONObject.toString();
            }).collect(Collectors.joining(","));
            return result.isBlank() ? "SEARCH_UNAVAILABLE: SearchAPI returned no usable result content." : result;
        } catch (Exception e) {
            return "SEARCH_UNAVAILABLE: Baidu search request failed: " + e.getMessage();
        }
    }

    private String searchWithTavily(String query) {
        try {
            String body = JSONUtil.toJsonStr(Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "search_depth", "basic",
                    "max_results", 5,
                    "include_answer", false
            ));
            String response = HttpRequest.post(TAVILY_API_URL)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .setConnectionTimeout(HTTP_TIMEOUT_MILLIS)
                    .setReadTimeout(HTTP_TIMEOUT_MILLIS)
                    .execute()
                    .body();
            JSONObject jsonObject = JSONUtil.parseObj(response);
            String error = jsonObject.getStr("error");
            if (error == null || error.isBlank()) error = jsonObject.getStr("detail");
            if (error != null && !error.isBlank()) return "SEARCH_UNAVAILABLE: Tavily returned an error: " + error;
            JSONArray results = jsonObject.getJSONArray("results");
            if (results == null || results.isEmpty()) return "SEARCH_UNAVAILABLE: Tavily returned no results for this query.";
            return results.subList(0, Math.min(results.size(), 5)).stream()
                    .map(item -> {
                        JSONObject result = JSONUtil.parseObj(item);
                        return JSONUtil.createObj()
                                .set("title", result.getStr("title"))
                                .set("link", result.getStr("url"))
                                .set("content", result.getStr("content"))
                                .set("score", result.get("score"))
                                .toString();
                    })
                    .collect(Collectors.joining(","));
        } catch (Exception e) {
            return "SEARCH_UNAVAILABLE: Tavily search request failed: " + e.getMessage();
        }
    }
}
