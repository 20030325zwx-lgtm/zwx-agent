package com.zwx.zwximagesearchmcpserver.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ImageSearchTool {

    private static final String API_URL = "https://api.pexels.com/v1/search";

    private final String apiKey;

    public ImageSearchTool(@Value("${pexels.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "Search Pexels for image URLs")
    public String searchImage(@ToolParam(description = "Search query keyword") String query) {
        try {
            List<String> images = searchMediumImages(query);
            return images.isEmpty() ? "No images found for this query" : String.join(",", images);
        } catch (Exception e) {
            return "Error search image: " + e.getMessage();
        }
    }

    public List<String> searchMediumImages(String query) {
        if (StrUtil.isBlank(query)) {
            throw new IllegalArgumentException("query is required");
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new IllegalStateException("PEXELS_API_KEY is not configured");
        }

        try (HttpResponse response = HttpUtil.createGet(API_URL)
                .header("Authorization", apiKey)
                .form("query", query)
                .timeout(15000)
                .execute()) {
            return parseImages(response.getStatus(), response.body());
        }
    }

    List<String> parseImages(int status, String body) {
        if (status == 401 || status == 403) {
            throw new IllegalStateException("Pexels rejected the API key (HTTP " + status + ")");
        }
        if (status == 429) {
            throw new IllegalStateException("Pexels rate limit reached (HTTP 429)");
        }
        if (status != 200) {
            throw new IllegalStateException("Pexels request failed (HTTP " + status + ")");
        }

        try {
            JSONObject payload = JSONUtil.parseObj(body);
            JSONArray photos = payload.getJSONArray("photos");
            if (photos == null) {
                throw new IllegalStateException("Pexels response has no photos array");
            }
            List<String> images = new ArrayList<>();
            for (Object item : photos) {
                if (item instanceof JSONObject photo) {
                    JSONObject src = photo.getJSONObject("src");
                    if (src != null && StrUtil.isNotBlank(src.getStr("medium"))) {
                        images.add(src.getStr("medium"));
                    }
                }
            }
            return images;
        } catch (cn.hutool.json.JSONException e) {
            throw new IllegalStateException("Pexels returned invalid JSON", e);
        }
    }
}
