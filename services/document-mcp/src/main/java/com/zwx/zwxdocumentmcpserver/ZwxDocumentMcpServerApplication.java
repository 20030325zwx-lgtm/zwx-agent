package com.zwx.zwxdocumentmcpserver;

import com.zwx.zwxdocumentmcpserver.tools.DocumentParsingTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ZwxDocumentMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZwxDocumentMcpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider documentTools(DocumentParsingTool documentParsingTool) {
        return MethodToolCallbackProvider.builder().toolObjects(documentParsingTool).build();
    }
}
