package com.zwx.zwxagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class DatabaseQueryToolTest {

    private DatabaseQueryTool tool(Set<String> allowlist) {
        return new DatabaseQueryTool(null, allowlist, null, 20, 5);
    }

    @Test
    void validSelectPassesValidation() {
        Assertions.assertNull(tool(Set.of()).validate("SELECT id, title FROM agent_conversation WHERE id = 1"));
        Assertions.assertNull(tool(Set.of()).validate("WITH recent AS (SELECT id FROM love_conversation) SELECT * FROM recent"));
    }

    @Test
    void nonSelectIsRejected() {
        String result = tool(Set.of()).executeDatabaseQuery("DELETE FROM love_conversation");
        Assertions.assertTrue(result.contains("read-only"), result);
    }

    @Test
    void multiStatementIsRejected() {
        String result = tool(Set.of()).executeDatabaseQuery("SELECT 1; DROP TABLE agent_conversation");
        Assertions.assertTrue(result.contains("single statement"), result);
    }

    @Test
    void commentIsRejected() {
        Assertions.assertNotNull(tool(Set.of()).validate("SELECT 1 -- hidden DELETE"));
        Assertions.assertNotNull(tool(Set.of()).validate("SELECT /* x */ 1"));
    }

    @Test
    void dataModifyingCteIsRejected() {
        String result = tool(Set.of()).executeDatabaseQuery(
                "WITH moved AS (INSERT INTO love_conversation (id) VALUES (1)) SELECT * FROM moved");
        Assertions.assertTrue(result.contains("not allowed"), result);
    }

    @Test
    void deniedTableIsRejected() {
        String result = tool(Set.of()).executeDatabaseQuery("SELECT username, password FROM app_user");
        Assertions.assertTrue(result.contains("denied"), result);
    }

    @Test
    void schemaQualifiedDeniedTableIsRejected() {
        String result = tool(Set.of()).executeDatabaseQuery("SELECT * FROM public.app_user");
        Assertions.assertTrue(result.contains("denied"), result);
    }

    @Test
    void tableAllowlistIsEnforced() {
        DatabaseQueryTool restricted = tool(Set.of("love_conversation"));
        String result = restricted.executeDatabaseQuery("SELECT * FROM agent_conversation");
        Assertions.assertTrue(result.contains("not in the allowlist"), result);
        Assertions.assertNull(restricted.validate("SELECT * FROM public.love_conversation"));
    }

    @Test
    void extractTablesHandlesCommaAndJoin() {
        java.util.Set<String> tables = DatabaseQueryTool.extractTables(
                "SELECT * FROM t1, t2 JOIN t3 ON t1.id = t3.id JOIN LATERAL f(t2) x ON true");
        Assertions.assertEquals(java.util.Set.of("t1", "t2", "t3"), tables);
    }

    @Test
    void offsetKeywordIsNotFlagged() {
        Assertions.assertNull(tool(Set.of()).validate("SELECT * FROM agent_conversation OFFSET 5"));
    }
}
