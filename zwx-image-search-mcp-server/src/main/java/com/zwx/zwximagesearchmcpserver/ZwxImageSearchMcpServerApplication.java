package com.zwx.zwximagesearchmcpserver;

import com.zwx.zwximagesearchmcpserver.tools.ImageSearchTool;
import com.zwx.zwximagesearchmcpserver.tools.CurrentTimeTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ZwxImageSearchMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZwxImageSearchMcpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider imageSearchTools(ImageSearchTool imageSearchTool, CurrentTimeTool currentTimeTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(imageSearchTool, currentTimeTool)
                .build();
    }

}
