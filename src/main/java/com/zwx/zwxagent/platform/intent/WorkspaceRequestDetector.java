package com.zwx.zwxagent.platform.intent;

import java.util.Locale;
import java.util.regex.Pattern;

/** 识别消息是否明确指向当前绑定工作区，不参与用户意图分类。 */
public final class WorkspaceRequestDetector {

    private static final Pattern CODE_STYLE_NAME = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Za-z0-9][A-Za-z0-9.]*(?:[-_][A-Za-z0-9][A-Za-z0-9.]*)+(?![A-Za-z0-9])");
    private static final String[] WORKSPACE_PHRASES = {
            "这个项目", "当前项目", "本项目", "项目下", "项目里", "工作区", "代码仓库", "代码库",
            "仓库里", "仓库下", "目录下", "源码", "源代码"
    };

    private WorkspaceRequestDetector() {
    }

    public static boolean referencesWorkspace(String message) {
        if (message == null || message.isBlank()) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        for (String phrase : WORKSPACE_PHRASES) {
            if (normalized.contains(phrase)) return true;
        }
        return CODE_STYLE_NAME.matcher(message).find();
    }
}
