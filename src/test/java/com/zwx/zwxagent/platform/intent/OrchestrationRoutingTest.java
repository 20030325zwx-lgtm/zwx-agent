package com.zwx.zwxagent.platform.intent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** 编排路由策略 v2：默认单循环，硬规则与并行证据升级图编排。 */
class OrchestrationRoutingTest {

    @Test
    void defaultsToSingleLoopForPlainRequests() {
        var decision = OrchestrationRouting.decide(false, false, "用一句话解释什么是幂等性", true);
        Assertions.assertTrue(decision.singleLoop());
        Assertions.assertTrue(decision.reason().contains("单循环"));

        // 长的线性任务也走单循环（步骤多不是拆分理由）
        var longLinear = OrchestrationRouting.decide(false, false,
                "帮我调研一下 Spring AI 的工具调用机制，整理成一篇带代码示例的说明", true);
        Assertions.assertTrue(longLinear.singleLoop());
    }

    @Test
    void hardRulesForceGraphMode() {
        Assertions.assertFalse(OrchestrationRouting.decide(true, false, "修一下这个项目", true).singleLoop());
        Assertions.assertFalse(OrchestrationRouting.decide(false, true, "看看这张图", true).singleLoop());
        // 总开关关闭：全量回图编排（零代码回滚路径）
        Assertions.assertFalse(OrchestrationRouting.decide(false, false, "你好", false).singleLoop());
    }

    @Test
    void parallelEvidenceEscalatesToGraph() {
        // 编号列表 ≥3 项
        String numbered = "帮我做三件事：\n1. 查天气\n2. 查限行\n3. 查汇率";
        Assertions.assertTrue(OrchestrationRouting.hasParallelEvidence(numbered));
        Assertions.assertFalse(OrchestrationRouting.decide(false, false, numbered, true).singleLoop());

        // 两项编号不算（保守）
        String twoItems = "1. 查天气\n2. 查限行";
        Assertions.assertFalse(OrchestrationRouting.hasParallelEvidence(twoItems));

        // 显式批量词
        Assertions.assertTrue(OrchestrationRouting.hasParallelEvidence("批量生成这 5 个 SKU 的海报"));
        Assertions.assertFalse(OrchestrationRouting.decide(false, false, "批量生成这 5 个 SKU 的海报", true).singleLoop());

        // 弱信号不触发："分别解释 A 和 B" 用词是 分别解释 而非 分别对/分别生
        Assertions.assertFalse(OrchestrationRouting.hasParallelEvidence("分别解释一下乐观锁和悲观锁"));
    }

    @Test
    void blankMessageIsSafe() {
        Assertions.assertFalse(OrchestrationRouting.hasParallelEvidence(null));
        Assertions.assertFalse(OrchestrationRouting.hasParallelEvidence("  "));
        Assertions.assertTrue(OrchestrationRouting.decide(false, false, null, true).singleLoop());
    }
}
