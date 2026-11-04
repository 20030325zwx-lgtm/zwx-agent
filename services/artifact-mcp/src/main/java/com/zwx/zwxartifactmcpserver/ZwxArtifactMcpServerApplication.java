package com.zwx.zwxartifactmcpserver;

import com.zwx.zwxartifactmcpserver.tools.ArtifactTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ZwxArtifactMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZwxArtifactMcpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider artifactTools(ArtifactTool artifactTool) {
        return MethodToolCallbackProvider.builder().toolObjects(artifactTool).build();
    }
}
