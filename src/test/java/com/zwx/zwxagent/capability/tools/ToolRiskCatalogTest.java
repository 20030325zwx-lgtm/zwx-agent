package com.zwx.zwxagent.capability.tools;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参数感知动态风险分级：终端纯环境查询命令降级，其余回落静态登记。 */
class ToolRiskCatalogTest {

    private static Optional<ToolRisk> dynamic(String argsJson) {
        return ToolRiskCatalog.dynamicRisk("executeTerminalCommand", argsJson);
    }

    @Test
    void inertEnvironmentCommandsAreReadOnly() {
        assertEquals(Optional.of(ToolRisk.READ_ONLY), dynamic("{\"command\": \"ls -la\"}"));
        assertEquals(Optional.of(ToolRisk.READ_ONLY), dynamic("{\"command\": \"pwd\"}"));
        assertEquals(Optional.of(ToolRisk.READ_ONLY), dynamic("{\"command\": \"date\"}"));
        assertEquals(Optional.of(ToolRisk.READ_ONLY), dynamic("{\"command\": \"echo hello\"}"));
        assertEquals(ToolRisk.READ_ONLY, ToolRiskCatalog.riskOf("executeTerminalCommand", "{\"command\": \"ls\"}"));
    }

    @Test
    void fileReadingCommandsStayDestructive() {
        // cat/grep/head/tail/find/python3 能读工作区外的宿主文件或执行脚本：不给动态降级
        assertTrue(dynamic("{\"command\": \"cat /etc/passwd\"}").isEmpty());
        assertTrue(dynamic("{\"command\": \"grep -rn TODO .\"}").isEmpty());
        assertTrue(dynamic("{\"command\": \"python3 script.py\"}").isEmpty());
        assertTrue(dynamic("{\"command\": \"find . -name x\"}").isEmpty());
    }

    @Test
    void metacharacterCommandsDoNotMatch() {
        // 含元字符的命令会被 ToolGuardPolicy 拒绝，动态规则不参与
        assertTrue(dynamic("{\"command\": \"ls; echo x\"}").isEmpty());
        assertTrue(dynamic("{\"command\": \"ls | wc\"}").isEmpty());
        assertTrue(dynamic("{\"command\": \"echo $HOME\"}").isEmpty());
        assertTrue(dynamic("{\"command\": \"echo `id`\"}").isEmpty());
    }

    @Test
    void malformedOrMissingArgumentsFallBackToStatic() {
        assertTrue(dynamic(null).isEmpty());
        assertTrue(dynamic("").isEmpty());
        assertTrue(dynamic("not-json").isEmpty());
        assertTrue(dynamic("{}").isEmpty());
        assertTrue(dynamic("{\"command\": \"   \"}").isEmpty());
        // 回落静态登记：终端为 DESTRUCTIVE
        assertEquals(ToolRisk.DESTRUCTIVE, ToolRiskCatalog.riskOf("executeTerminalCommand", "not-json"));
    }

    @Test
    void staticCatalogLevels() {
        assertEquals(ToolRisk.FORBIDDEN, ToolRiskCatalog.riskOf("queryExternalDatabase"));
        assertEquals(ToolRisk.EXECUTE, ToolRiskCatalog.riskOf("unknownFutureTool"));
        assertEquals(ToolRisk.EXECUTE, ToolRiskCatalog.riskOf("mcp:filesystem"));
        assertEquals(ToolRisk.READ_ONLY, ToolRiskCatalog.riskOf("readFile"));
        // 其他工具名不受动态规则影响
        assertEquals(Optional.empty(), ToolRiskCatalog.dynamicRisk("writeFile", "{\"fileName\": \"a.md\"}"));
    }
}
