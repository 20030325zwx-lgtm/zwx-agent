package com.zwx.zwxagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabaseQueryTool {

    private static final int MAX_SQL_LENGTH = 4_000;
    private static final int MAX_OUTPUT_CHARS = 8_000;

    private static final Set<String> FORBIDDEN_KEYWORDS = new HashSet<>(Arrays.asList(
            "insert", "update", "delete", "drop", "alter", "create", "truncate", "grant", "revoke",
            "call", "copy", "vacuum", "merge", "lock", "comment", "reindex", "cluster", "set",
            "reset", "into", "listen", "notify", "do", "for"));

    private static final Set<String> FORBIDDEN_FUNCTIONS = new HashSet<>(Arrays.asList(
            "dblink", "lo_import", "lo_export", "pg_read_file", "pg_read_binary_file", "pg_ls_dir",
            "pg_sleep", "pg_advisory_lock", "pg_terminate_backend", "pg_reload_conf", "pg_rewrite_query"));

    private static final Set<String> DEFAULT_DENIED_TABLES = new HashSet<>(Arrays.asList(
            "app_user", "flyway_schema_history"));

    private static final Pattern FORBIDDEN_COMMENT = Pattern.compile("(--|/\\*)");
    private static final Pattern FROM_JOIN_PATTERN = Pattern.compile("\\b(?:from|join)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-z_][\\w$]*\\b");

    private final DataSource dataSource;
    private final Set<String> deniedTables;
    private final Set<String> allowedTables;
    private final int maxRows;
    private final int timeoutSeconds;

    public DatabaseQueryTool(DataSource dataSource,
                             Set<String> allowedTables,
                             Set<String> deniedTables,
                             int maxRows,
                             int timeoutSeconds) {
        this.dataSource = dataSource;
        this.allowedTables = allowedTables == null ? Set.of() : lowercase(allowedTables);
        this.deniedTables = deniedTables == null || deniedTables.isEmpty()
                ? DEFAULT_DENIED_TABLES
                : lowercase(deniedTables);
        this.maxRows = Math.max(1, maxRows);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    @Tool(description = "Execute a single read-only SELECT query against the application database and return the rows as text")
    public String executeDatabaseQuery(@ToolParam(description = "A single read-only SQL SELECT or WITH query statement") String sql) {
        String error = validate(sql);
        if (error != null) {
            return "Error executing query: " + error;
        }
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setMaxRows(maxRows + 1);
                try (ResultSet resultSet = statement.executeQuery(trimmed)) {
                    String result = formatResult(resultSet, maxRows);
                    connection.rollback();
                    return result;
                }
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            return "Error executing query: " + exception.getMessage();
        }
    }

    String validate(String sql) {
        String syntaxError = validateSyntax(sql);
        if (syntaxError != null) {
            return syntaxError;
        }
        String normalized = sql.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (String table : extractTables(normalized)) {
            String name = lastSegment(table);
            if (deniedTables.contains(name)) {
                return "the table '" + name + "' is denied";
            }
            if (!allowedTables.isEmpty() && !allowedTables.contains(name)) {
                return "the table '" + name + "' is not in the allowlist " + allowedTables;
            }
        }
        return null;
    }

    static String validateSyntax(String sql) {
        if (sql == null || sql.isBlank()) {
            return "the SQL statement is empty";
        }
        String trimmed = sql.trim();
        if (trimmed.length() > MAX_SQL_LENGTH) {
            return "the SQL statement exceeds the " + MAX_SQL_LENGTH + " character limit";
        }
        String normalized = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (normalized.contains(";")) {
            return "only a single statement is allowed";
        }
        if (FORBIDDEN_COMMENT.matcher(normalized).find()) {
            return "SQL comments are not allowed";
        }
        String lower = normalized.toLowerCase();
        String firstWord = firstWord(lower);
        if (!"select".equals(firstWord) && !"with".equals(firstWord)) {
            return "only read-only SELECT queries are allowed";
        }
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (containsWord(lower, keyword)) {
                return "the keyword '" + keyword + "' is not allowed in a read-only query";
            }
        }
        for (String function : FORBIDDEN_FUNCTIONS) {
            if (lower.contains(function)) {
                return "the function '" + function + "' is not allowed";
            }
        }
        return null;
    }

    static String formatResult(ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int index = 1; index <= columnCount; index++) {
            columns.add(metaData.getColumnLabel(index));
        }
        List<String[]> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            String[] row = new String[columnCount];
            for (int index = 1; index <= columnCount; index++) {
                Object value = resultSet.getObject(index);
                row[index - 1] = value == null ? "NULL" : String.valueOf(value);
            }
            rows.add(row);
        }
        StringBuilder output = new StringBuilder();
        output.append(rows.size()).append(" row(s)").append(truncated ? " (truncated at max " + maxRows + " rows)" : "").append("\n");
        output.append(String.join(" | ", columns)).append("\n");
        for (String[] row : rows) {
            output.append(String.join(" | ", row)).append("\n");
            if (output.length() > MAX_OUTPUT_CHARS) {
                return output.substring(0, MAX_OUTPUT_CHARS) + "\n...[output truncated]";
            }
        }
        return output.toString();
    }

    static Set<String> extractTables(String normalizedSql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = FROM_JOIN_PATTERN.matcher(normalizedSql);
        while (matcher.find()) {
            int index = matcher.end();
            int length = normalizedSql.length();
            while (true) {
                index = skipSpaces(normalizedSql, index);
                if (regionMatchesKeyword(normalizedSql, index, "lateral")
                        || regionMatchesKeyword(normalizedSql, index, "only")) {
                    index += normalizedSql.regionMatches(true, index, "lateral", 0, 7) ? 7 : 4;
                    continue;
                }
                if (index >= length || !isIdentifierStart(normalizedSql.charAt(index))) {
                    break;
                }
                int[] identifierEnd = readIdentifier(normalizedSql, index);
                String identifier = normalizedSql.substring(identifierEnd[0], identifierEnd[1]);
                index = skipSpaces(normalizedSql, identifierEnd[1]);
                boolean functionCall = index < length && normalizedSql.charAt(index) == '(';
                if (functionCall) {
                    index = skipBalancedParentheses(normalizedSql, index);
                    index = skipSpaces(normalizedSql, index);
                }
                if (regionMatchesKeyword(normalizedSql, index, "as")) {
                    index = skipSpaces(normalizedSql, index + 2);
                    if (index < length && isIdentifierStart(normalizedSql.charAt(index))) {
                        index = readIdentifier(normalizedSql, index)[1];
                        index = skipSpaces(normalizedSql, index);
                    }
                } else if (index < length && isIdentifierStart(normalizedSql.charAt(index))
                        && !isContinuationKeyword(normalizedSql, index)) {
                    index = readIdentifier(normalizedSql, index)[1];
                    index = skipSpaces(normalizedSql, index);
                }
                if (!functionCall) {
                    tables.add(identifier.toLowerCase());
                }
                if (index < length && normalizedSql.charAt(index) == ',') {
                    index++;
                    continue;
                }
                break;
            }
        }
        return tables;
    }

    private static int[] readIdentifier(String text, int start) {
        int index = start;
        int length = text.length();
        while (index < length && isIdentifierPart(text.charAt(index))) index++;
        while (index + 1 < length && text.charAt(index) == '.' && isIdentifierStart(text.charAt(index + 1))) {
            index++;
            start = index;
            while (index < length && isIdentifierPart(text.charAt(index))) index++;
        }
        return new int[]{start, index};
    }

    private static int skipBalancedParentheses(String text, int openIndex) {
        int depth = 0;
        int index = openIndex;
        int length = text.length();
        while (index < length) {
            char character = text.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
            index++;
        }
        return length;
    }

    private static boolean isContinuationKeyword(String text, int offset) {
        for (String keyword : new String[]{"where", "group", "order", "limit", "offset", "having", "window",
                "on", "join", "inner", "left", "right", "full", "outer", "cross", "natural", "using",
                "union", "except", "intersect", "fetch"}) {
            if (regionMatchesKeyword(text, offset, keyword)) {
                return true;
            }
        }
        return false;
    }

    private static int skipSpaces(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private static boolean regionMatchesKeyword(String text, int offset, String keyword) {
        if (offset + keyword.length() > text.length()) {
            return false;
        }
        if (!text.regionMatches(true, offset, keyword, 0, keyword.length())) {
            return false;
        }
        int end = offset + keyword.length();
        return end >= text.length() || !isIdentifierPart(text.charAt(end));
    }

    private static boolean isIdentifierStart(char character) {
        return Character.isLetter(character) || character == '_';
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '$';
    }

    private static Set<String> lowercase(Set<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim().toLowerCase());
            }
        }
        return result;
    }

    private static String firstWord(String lowerSql) {
        Matcher matcher = WORD_PATTERN.matcher(lowerSql);
        return matcher.find() ? matcher.group() : "";
    }

    private static boolean containsWord(String lowerSql, String keyword) {
        Matcher matcher = WORD_PATTERN.matcher(lowerSql);
        while (matcher.find()) {
            if (matcher.group().equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String lastSegment(String identifier) {
        int dot = identifier.lastIndexOf('.');
        return dot >= 0 ? identifier.substring(dot + 1) : identifier;
    }
}
