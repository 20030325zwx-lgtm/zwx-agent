package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.capability.mcp.McpConnectionTestResult;
import com.zwx.zwxagent.capability.mcp.McpServerConfiguration;
import com.zwx.zwxagent.capability.mcp.McpServerConfigurationRequest;
import com.zwx.zwxagent.capability.mcp.McpServerConfigurationService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** MCP 服务管理接口（管理员）：租户级连接配置 CRUD 与连通性测试。原 AiController mcp 组拆分，路径保持不变。 */
@RestController
@RequestMapping("/ai")
public class McpAdminController {

    @Resource
    private McpServerConfigurationService mcpServerConfigurationService;

    @GetMapping("/mcp/servers")
    public List<McpServerConfiguration> listMcpServers(CurrentActor actor) {
        return mcpServerConfigurationService.list(actor.tenantId());
    }

    @PostMapping("/mcp/servers")
    public McpServerConfiguration createMcpServer(CurrentActor actor, @RequestBody McpServerConfigurationRequest request) {
        actor.requireAdmin();
        return mcpServerConfigurationService.create(actor.tenantId(), request);
    }

    @PutMapping("/mcp/servers/{id}")
    public McpServerConfiguration updateMcpServer(CurrentActor actor, @PathVariable long id, @RequestBody McpServerConfigurationRequest request) {
        actor.requireAdmin();
        return mcpServerConfigurationService.update(actor.tenantId(), id, request);
    }

    @DeleteMapping("/mcp/servers/{id}")
    public void deleteMcpServer(CurrentActor actor, @PathVariable long id) {
        actor.requireAdmin();
        mcpServerConfigurationService.delete(actor.tenantId(), id);
    }

    @PostMapping("/mcp/servers/{id}/test")
    public McpConnectionTestResult testMcpServer(CurrentActor actor, @PathVariable long id) {
        actor.requireAdmin();
        return mcpServerConfigurationService.test(actor.tenantId(), id);
    }
}
