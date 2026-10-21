package com.zwx.zwxagent.capability.tools;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGuardPolicyTest {

    @Test
    void allowlistedSimpleCommandPasses() {
        assertNull(ToolGuardPolicy.terminalViolation("ls -la", null));
        assertNull(ToolGuardPolicy.terminalViolation("grep -r \"hello world\" notes.txt", Set.of("grep")));
    }

    @Test
    void nonAllowlistedHeadIsRejected() {
        String reason = ToolGuardPolicy.terminalViolation("rm -rf /", null);
        assertTrue(reason.contains("not in the allowlist"));
    }

    @Test
    void shellMetacharacterChainingIsRejected() {
        // 原 allowlist 只查首 token，这类命令曾可绕过
        assertTrue(ToolGuardPolicy.terminalViolation("ls; cat /etc/passwd", null).contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("echo ok && rm -rf ~", null).contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("ls | sh", null).contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("echo `whoami`", null).contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("echo $(whoami)", null).contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("echo $HOME", null).contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("cat notes > /tmp/out", null).contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("ls;\ncat /etc/passwd", null).contains("metacharacter"));
    }

    @Test
    void quotedAbsolutePathsAreRejected() {
        assertTrue(ToolGuardPolicy.terminalViolation("cat \"/etc/passwd\"", null).contains("absolute path"));
        assertTrue(ToolGuardPolicy.terminalViolation("cat '/etc/passwd'", null).contains("absolute path"));
    }

    @Test
    void traversalAndHomePathsAreRejected() {
        assertTrue(ToolGuardPolicy.terminalViolation("cat ../secret.txt", null).contains("traversal"));
        assertTrue(ToolGuardPolicy.terminalViolation("grep x ../../etc/passwd", null).contains("traversal"));
        assertTrue(ToolGuardPolicy.terminalViolation("cat ~/notes.txt", null).contains("home-relative"));
    }

    @Test
    void findExecAndDeleteAreRejected() {
        // \; 含元字符，先被元字符检查拦截
        assertTrue(ToolGuardPolicy.terminalViolation("find . -name a -exec rm {} ;", null).contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("find . -execdir pwd", null).contains("-execdir"));
        assertTrue(ToolGuardPolicy.terminalViolation("find . -name a -delete", null).contains("-delete"));
        assertNull(ToolGuardPolicy.terminalViolation("find . -name \"*.txt\"", null));
    }

    @Test
    void python3OptionIsRejectedButWorkspaceScriptPasses() {
        assertTrue(ToolGuardPolicy.terminalViolation("python3 -V", null).contains("python3 option"));
        // -c 内嵌 ; 时先被元字符检查拦截（同为拒绝，原因不同）
        assertTrue(ToolGuardPolicy.terminalViolation("python3 -c \"import os; os.system('ls')\"", null)
                .contains("metacharacter"));
        assertTrue(ToolGuardPolicy.terminalViolation("python3 /tmp/evil.py", null).contains("absolute path"));
        assertNull(ToolGuardPolicy.terminalViolation("python3 analyze.py --input data.csv", null));
    }

    @Test
    void emptyAndBlankCommandsAreRejected() {
        assertTrue(ToolGuardPolicy.terminalViolation("", null).contains("empty"));
        assertTrue(ToolGuardPolicy.terminalViolation(null, null).contains("empty"));
    }

    @Test
    void relativePathGuardRejectsEscapeAndAcceptsPlainNames() {
        assertNull(ToolGuardPolicy.relativePathViolation("report.pdf"));
        assertNull(ToolGuardPolicy.relativePathViolation("sub/dir/file.txt"));
        assertTrue(ToolGuardPolicy.relativePathViolation("/etc/passwd").contains("absolute path"));
        assertTrue(ToolGuardPolicy.relativePathViolation("C:\\win\\tmp").contains("absolute path"));
        assertTrue(ToolGuardPolicy.relativePathViolation("..\\secret").contains("traversal"));
        assertTrue(ToolGuardPolicy.relativePathViolation("~/x").contains("home-relative"));
        assertTrue(ToolGuardPolicy.relativePathViolation(" ").contains("required"));
        assertTrue(ToolGuardPolicy.relativePathViolation(null).contains("required"));
        assertTrue(ToolGuardPolicy.relativePathViolation("a\0b").contains("illegal"));
    }
}
