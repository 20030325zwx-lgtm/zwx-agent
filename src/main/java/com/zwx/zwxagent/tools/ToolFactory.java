package com.zwx.zwxagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class ToolFactory {

    private final ToolSandbox sandbox;
    private final UrlAccessPolicy urlAccessPolicy;
    private final WebSearchTool webSearchTool;
    private final boolean terminalEnabled;
    private final long maxDownloadBytes;
    private final Set<String> terminalAllowlist;

    public ToolFactory(ToolSandbox sandbox,
                       UrlAccessPolicy urlAccessPolicy,
                       @Value("${search-api.api-key}") String searchApiKey,
                       @Value("${search-api.provider:searchapi}") String searchProvider,
                       @Value("${app.tools.terminal-enabled:false}") boolean terminalEnabled,
                       @Value("${app.tools.max-download-bytes:20971520}") long maxDownloadBytes,
                       @Value("${app.tools.terminal-allowlist:}") String terminalAllowlist) {
        this.sandbox = sandbox;
        this.urlAccessPolicy = urlAccessPolicy;
        this.webSearchTool = new WebSearchTool(searchProvider, searchApiKey);
        this.terminalEnabled = terminalEnabled;
        this.maxDownloadBytes = maxDownloadBytes;
        this.terminalAllowlist = terminalAllowlist == null || terminalAllowlist.isBlank()
                ? null
                : new HashSet<>(Arrays.asList(terminalAllowlist.split(",")));
    }

    public ToolCallback[] createTools(String scope) {
        Path workDir = sandbox.scopeDir(scope);
        java.util.List<Object> tools = new java.util.ArrayList<>(java.util.List.of(
                new FileOperationTool(sandbox, workDir),
                webSearchTool,
                new WebScrapingTool(urlAccessPolicy),
                new ResourceDownloadTool(urlAccessPolicy, sandbox, workDir, maxDownloadBytes),
                new PDFGenerationTool(sandbox, workDir)));
        if (terminalEnabled) {
            tools.add(new TerminalOperationTool(workDir, terminalAllowlist));
        }
        return ToolCallbacks.from(tools.toArray());
    }
}
