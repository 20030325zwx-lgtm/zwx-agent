package com.zwx.zwxagent.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopGuardTest {

    @Test
    void emptyObservationDoesNotCountAnything() {
        ToolLoopGuard guard = new ToolLoopGuard();

        for (int i = 0; i < 10; i++) {
            assertEquals(Optional.empty(), guard.observe(List.of()));
        }
    }

    @Test
    void sameArgsTripRequiresThreeUnproductiveCalls() {
        ToolLoopGuard guard = new ToolLoopGuard();

        assertEquals(Optional.empty(), guard.observe(execution("searchWeb", "{q:1}", "error: x")));
        assertEquals(Optional.empty(), guard.observe(execution("searchWeb", "{q:1}", "error: y")));
        Optional<String> verdict = guard.observe(execution("searchWeb", "{q:1}", "error: z"));
        assertTrue(verdict.isPresent());
        assertTrue(verdict.get().contains("相同参数重复调用 3 次"));
        assertTrue(verdict.get().contains("searchWeb"));
    }

    @Test
    void productiveRoundExemptsArgsRepeatAndResetsNoProgress() {
        ToolLoopGuard guard = new ToolLoopGuard();

        // 4 轮无进展（参数各不相同），未到 5 轮阈值
        for (int i = 1; i <= 4; i++) {
            assertEquals(Optional.empty(),
                    guard.observe(execution("searchWeb", "{q:" + i + "}", "error: x")));
        }
        // 1 轮有新有效结果：清零无进展计数，且豁免参数循环判定
        assertEquals(Optional.empty(), guard.observe(execution("searchWeb", "{q:1}", "fresh content")));
        // 之后重新累计，仍需连续 5 轮无进展才停
        for (int i = 1; i <= 4; i++) {
            assertEquals(Optional.empty(),
                    guard.observe(execution("searchWeb", "{q:" + (10 + i) + "}", "error: x")));
        }
        Optional<String> verdict = guard.observe(execution("searchWeb", "{q:99}", "error: x"));
        assertTrue(verdict.isPresent());
        assertTrue(verdict.get().contains("连续 5 轮没有获得新的有效结果"));
    }

    @Test
    void staleResultViaDifferentArgsDoesNotCountAsProgress() {
        ToolLoopGuard guard = new ToolLoopGuard();

        // 首轮结果算新（放行），之后同样内容换参数反复拿：从第 2 轮起累计无进展
        assertEquals(Optional.empty(), guard.observe(execution("searchWeb", "{q:1}", "same content")));
        for (int i = 2; i <= 5; i++) {
            assertEquals(Optional.empty(),
                    guard.observe(execution("searchWeb", "{q:" + i + "}", "same content")));
        }
        Optional<String> verdict = guard.observe(execution("searchWeb", "{q:6}", "same content"));
        assertTrue(verdict.isPresent());
        assertTrue(verdict.get().contains("连续 5 轮没有获得新的有效结果"));
    }

    private static List<BaseAgent.ToolExecution> execution(String tool, String args, String result) {
        return List.of(new BaseAgent.ToolExecution(tool, args, result));
    }
}
