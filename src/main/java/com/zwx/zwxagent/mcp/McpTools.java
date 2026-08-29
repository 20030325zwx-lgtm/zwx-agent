package com.zwx.zwxagent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public record McpTools(ToolCallback[] callbacks, List<McpSyncClient> clients) implements AutoCloseable {
    public static final McpTools EMPTY = new McpTools(new ToolCallback[0], List.of());

    @Override
    public void close() {
        clients.forEach(client -> {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
                client.close();
            }
        });
    }
}
