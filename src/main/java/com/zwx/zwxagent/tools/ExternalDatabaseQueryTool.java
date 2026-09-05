package com.zwx.zwxagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

public class ExternalDatabaseQueryTool {

    private static final Set<String> SUPPORTED_TYPES = Set.of("postgresql", "mysql");

    private final int maxRows;
    private final int timeoutSeconds;

    public ExternalDatabaseQueryTool(int maxRows, int timeoutSeconds) {
        this.maxRows = Math.max(1, maxRows);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    @Tool(description = "Connect to a user-provided PostgreSQL or MySQL database and run a single read-only SELECT query")
    public String queryExternalDatabase(@ToolParam(description = "Database type: postgresql or mysql") String databaseType,
                                        @ToolParam(description = "Database host or IP address") String host,
                                        @ToolParam(description = "Database port, for example 5432 for postgresql or 3306 for mysql") Integer port,
                                        @ToolParam(description = "Database name") String database,
                                        @ToolParam(description = "Database username") String username,
                                        @ToolParam(description = "Database password") String password,
                                        @ToolParam(description = "A single read-only SQL SELECT or WITH query statement") String sql) {
        String type = databaseType == null ? "" : databaseType.trim().toLowerCase();
        if (!SUPPORTED_TYPES.contains(type)) {
            return "Error executing query: unsupported database type '" + databaseType + "', supported: " + SUPPORTED_TYPES;
        }
        if (host == null || host.isBlank() || database == null || database.isBlank()) {
            return "Error executing query: host and database are required";
        }
        String syntaxError = DatabaseQueryTool.validateSyntax(sql);
        if (syntaxError != null) {
            return "Error executing query: " + syntaxError;
        }
        int effectivePort = port != null && port > 0 ? port : ("postgresql".equals(type) ? 5432 : 3306);
        String statement = sql.trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1);
        }
        String url = buildUrl(type, host.trim(), effectivePort, database.trim());
        try {
            DriverManager.setLoginTimeout(timeoutSeconds);
            try (Connection connection = DriverManager.getConnection(url, username == null ? "" : username.trim(),
                    password == null ? "" : password)) {
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                try (Statement jdbcStatement = connection.createStatement()) {
                    jdbcStatement.setQueryTimeout(timeoutSeconds);
                    jdbcStatement.setMaxRows(maxRows + 1);
                    try (ResultSet resultSet = jdbcStatement.executeQuery(statement)) {
                        String result = DatabaseQueryTool.formatResult(resultSet, maxRows);
                        connection.rollback();
                        return result;
                    }
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException exception) {
            return "Error executing query: " + exception.getMessage();
        }
    }

    static String buildUrl(String type, String host, int port, String database) {
        if ("postgresql".equals(type)) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database
                    + "?connectTimeout=" + 5 + "&socketTimeout=" + 30;
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?connectTimeout=5000&socketTimeout=30000";
    }
}
