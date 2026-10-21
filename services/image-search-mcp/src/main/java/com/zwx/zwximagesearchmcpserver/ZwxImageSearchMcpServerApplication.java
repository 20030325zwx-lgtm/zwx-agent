package com.zwx.zwximagesearchmcpserver;

import com.zwx.zwximagesearchmcpserver.tools.ImageSearchTool;
import com.zwx.zwximagesearchmcpserver.tools.ImageUnderstandingTool;
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
    public ToolCallbackProvider imageSearchTools(ImageSearchTool imageSearchTool,
                                                 ImageUnderstandingTool imageUnderstandingTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(imageSearchTool, imageUnderstandingTool)
                .build();
    }

}
