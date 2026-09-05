package com.zwx.zwxagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ExternalDatabaseQueryToolTest {

    private final ExternalDatabaseQueryTool tool = new ExternalDatabaseQueryTool(20, 5);

    @Test
    void unsupportedDatabaseTypeIsRejected() {
        String result = tool.queryExternalDatabase("oracle", "127.0.0.1", 1521, "orcl", "u", "p", "SELECT 1");
        Assertions.assertTrue(result.contains("unsupported database type"), result);
    }

    @Test
    void missingHostOrDatabaseIsRejected() {
        String result = tool.queryExternalDatabase("postgresql", "", null, "db", "u", "p", "SELECT 1");
        Assertions.assertTrue(result.contains("host and database are required"), result);
    }

    @Test
    void nonSelectIsRejected() {
        String result = tool.queryExternalDatabase("mysql", "10.0.0.8", 3306, "shop", "u", "p",
                "UPDATE users SET name = 'x'");
        Assertions.assertTrue(result.contains("read-only"), result);
    }

    @Test
    void multiStatementIsRejected() {
        String result = tool.queryExternalDatabase("postgresql", "10.0.0.8", 5432, "shop", "u", "p",
                "SELECT 1; SELECT 2");
        Assertions.assertTrue(result.contains("single statement"), result);
    }

    @Test
    void buildUrlDefaultsAndParams() {
        Assertions.assertEquals("jdbc:postgresql://db.example.com:5432/shop?connectTimeout=5&socketTimeout=30",
                ExternalDatabaseQueryTool.buildUrl("postgresql", "db.example.com", 5432, "shop"));
        Assertions.assertEquals("jdbc:mysql://10.1.1.1:3307/shop?connectTimeout=5000&socketTimeout=30000",
                ExternalDatabaseQueryTool.buildUrl("mysql", "10.1.1.1", 3307, "shop"));
    }
}
