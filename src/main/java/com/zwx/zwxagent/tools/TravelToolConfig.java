package com.zwx.zwxagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TravelToolConfig {

    @Bean("travelTools")
    ToolCallback[] travelTools(@Value("${search-api.api-key}") String searchApiKey) {
        return ToolCallbacks.from(new WebSearchTool(searchApiKey));
    }
}
