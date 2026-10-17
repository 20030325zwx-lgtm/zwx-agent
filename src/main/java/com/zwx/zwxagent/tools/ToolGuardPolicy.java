package com.zwx.zwxagent.tools;

import java.util.Set;

/**
 * 工具调用中央安全策略（纯函数，无状态）。
 * ToolGuardHook（第一道，中央审计）与 TerminalOperationTool（第二道，工具本地）共用，
 * 保证绕过 hook 的调用路径同样受限。
 * 已知限制：python3 脚本自身仍可读宿主文件，进程级真隔离需容器沙箱（design/plans/02 后续项）。
 */
public final class ToolGuardPolicy {

    public static final Set<String> DEFAULT_TERMINAL_ALLOWED = Set.of(
            "ls", "cat", "pwd", "echo", "head", "tail", "wc", "find", "date", "grep", "python3");

    /** find 可执行任意命令或绕过工作区的旗标 */
    private static final Set<String> FIND_DANGEROUS_FLAGS = Set.of(
            "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprintf", "-fls");

    private ToolGuardPolicy() {
    }

    /**
     * 校验一条待进 shell 的命令。
     *
     * @return null 表示通过；非 null 为面向用户的拒绝原因（不含前缀）
     */
    public static String terminalViolation(String command, Set<String> allowedCommands) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return "empty command";
        }
        String[] tokens = trimmed.split("\\s+");
        // sh -c 元字符（引号内出现也拒绝，宁可错杀）：阻断命令拼接、管道、重定向与 $() 变量替换。
        // 必须在 allowlist 之前：如 "ls; cat x" 的首 token 是 "ls;"，allowlist 检查会掩盖元字符问题
        for (String bad : new String[]{";", "|", "&", ">", "<", "`", "$", "\n", "\r"}) {
            if (trimmed.contains(bad)) {
                return "shell metacharacter '" + bad + "' is not allowed";
            }
        }
        Set<String> allow = allowedCommands == null || allowedCommands.isEmpty()
                ? DEFAULT_TERMINAL_ALLOWED : allowedCommands;
        if (!allow.contains(tokens[0])) {
            return "command '" + tokens[0] + "' is not in the allowlist " + allow;
        }
        for (String token : tokens) {
            String bare = stripQuotes(token);
            if (FIND_DANGEROUS_FLAGS.contains(bare)) {
                return "find flag '" + bare + "' is not allowed";
            }
            String pathReason = relativePathViolation(bare);
            if (pathReason != null) {
                return pathReason;
            }
        }
        if ("python3".equals(tokens[0]) && tokens.length > 1 && tokens[1].startsWith("-")) {
            // 仅脚本名之前的 token 视为解释器选项（-c/-m 等）；脚本自身的参数（--input x）放行
            return "python3 option '" + tokens[1] + "' is not allowed; run a workspace script instead";
        }
        return null;
    }

    /**
     * 校验应为工作区内相对路径的文件名参数（去引号后判断）。
     *
     * @return null 表示通过；非 null 为拒绝原因
     */
    public static String relativePathViolation(String fileName) {
        String bare = stripQuotes(fileName == null ? "" : fileName.trim());
        if (bare.isEmpty()) {
            return "file name is required";
        }
        if (bare.indexOf('\0') >= 0) {
            return "file name contains illegal characters";
        }
        if (bare.startsWith("/") || bare.startsWith("\\") || bare.matches("^[A-Za-z]:.*")) {
            return "absolute path '" + bare + "' is not allowed; use paths inside the workspace";
        }
        if (bare.startsWith("~")) {
            return "home-relative path '" + bare + "' is not allowed";
        }
        if (bare.equals("..") || bare.startsWith("../") || bare.contains("/../") || bare.endsWith("/..")
                || bare.startsWith("..\\") || bare.contains("\\..\\")) {
            return "path traversal '" + bare + "' is not allowed";
        }
        return null;
    }

    /** 剥掉一层成对引号（最多两轮），防御 "cat "/etc/passwd"" 式引号绕过。 */
    private static String stripQuotes(String token) {
        String result = token;
        for (int i = 0; i < 2 && result.length() >= 2; i++) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                result = result.substring(1, result.length() - 1);
            } else {
                break;
            }
        }
        return result;
    }
}
