package com.zwx.zwxagent.platform.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceRequestDetectorTest {

    @Test
    void detectsWorkspacePhrasesAndCodeStyleNames() {
        assertTrue(WorkspaceRequestDetector.referencesWorkspace("你了解下这个项目下的简历服务"));
        assertTrue(WorkspaceRequestDetector.referencesWorkspace("那你聊聊 aster-mcp"));
        assertTrue(WorkspaceRequestDetector.referencesWorkspace("看看 zwx-resume-site"));
    }

    @Test
    void leavesOrdinaryConversationAlone() {
        assertFalse(WorkspaceRequestDetector.referencesWorkspace("你好，在吗"));
        assertFalse(WorkspaceRequestDetector.referencesWorkspace("讲个笑话"));
        assertFalse(WorkspaceRequestDetector.referencesWorkspace(null));
    }
}
