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
    private final javax.sql.DataSource dataSource;
    private final boolean dbQueryEnabled;
    private final Set<String> dbQueryTableAllowlist;
    private final Set<String> dbQueryDeniedTables;
    private final int dbQueryMaxRows;
    private final int dbQueryTimeoutSeconds;
    private final boolean dbQueryExternalEnabled;

    public ToolFactory(ToolSandbox sandbox,
                       UrlAccessPolicy urlAccessPolicy,
                       @Value("${search-api.api-key}") String searchApiKey,
                       @Value("${search-api.provider:searchapi}") String searchProvider,
                       @Value("${app.tools.terminal-enabled:false}") boolean terminalEnabled,
                       @Value("${app.tools.max-download-bytes:20971520}") long maxDownloadBytes,
                       @Value("${app.tools.terminal-allowlist:}") String terminalAllowlist,
                       javax.sql.DataSource dataSource,
                       @Value("${app.tools.db-query-enabled:false}") boolean dbQueryEnabled,
                       @Value("${app.tools.db-query-table-allowlist:}") String dbQueryTableAllowlist,
                       @Value("${app.tools.db-query-denied-tables:}") String dbQueryDeniedTables,
                       @Value("${app.tools.db-query-max-rows:20}") int dbQueryMaxRows,
                       @Value("${app.tools.db-query-timeout-seconds:5}") int dbQueryTimeoutSeconds,
                       @Value("${app.tools.db-query-external-enabled:false}") boolean dbQueryExternalEnabled) {
        this.sandbox = sandbox;
        this.urlAccessPolicy = urlAccessPolicy;
        this.webSearchTool = new WebSearchTool(searchProvider, searchApiKey);
        this.terminalEnabled = terminalEnabled;
        this.maxDownloadBytes = maxDownloadBytes;
        this.terminalAllowlist = terminalAllowlist == null || terminalAllowlist.isBlank()
                ? null
                : new HashSet<>(Arrays.asList(terminalAllowlist.split(",")));
        this.dataSource = dataSource;
        this.dbQueryEnabled = dbQueryEnabled;
        this.dbQueryTableAllowlist = toSet(dbQueryTableAllowlist);
        this.dbQueryDeniedTables = toSet(dbQueryDeniedTables);
        this.dbQueryMaxRows = dbQueryMaxRows;
        this.dbQueryTimeoutSeconds = dbQueryTimeoutSeconds;
        this.dbQueryExternalEnabled = dbQueryExternalEnabled;
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
        if (dbQueryEnabled) {
            tools.add(new DatabaseQueryTool(dataSource, dbQueryTableAllowlist, dbQueryDeniedTables,
                    dbQueryMaxRows, dbQueryTimeoutSeconds));
        }
        if (dbQueryExternalEnabled) {
            tools.add(new ExternalDatabaseQueryTool(dbQueryMaxRows, dbQueryTimeoutSeconds));
        }
        return ToolCallbacks.from(tools.toArray());
    }

    private static Set<String> toSet(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(commaSeparated.split(",")));
    }
}
