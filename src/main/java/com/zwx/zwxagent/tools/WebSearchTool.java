package com.zwx.zwxagent.tools;

import cn.hutool.http.HttpUtil;
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

    // SearchAPI 的搜索接口地址
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    private final String apiKey;

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query) {
        if (apiKey == null || apiKey.isBlank()) return "SEARCH_UNAVAILABLE: SearchAPI key is not configured.";
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);
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
}
