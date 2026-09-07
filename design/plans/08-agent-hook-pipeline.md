# Agent Hook 管线（可插拔中间件）方案

> 状态：阶段 1（空管线插桩 + 单测）已实施，见 `context/session-2026-09-07-knowledge-versioning.md`；阶段 2/3 尚未实施
>
> 目标：把 BaseAgent / ToolCallAgent 执行链路上的横切逻辑（trace、活动事件、死循环检测、沙箱、后续的技能注入与记忆压缩）从核心类中拆出，收敛为有序、可插拔、可短路的 hook 管线；核心循环退化为纯「思考 → 行动」骨架。

## 1. 背景与现状

### 1.1 现有执行链

```text
BaseAgent.runStream(userPrompt)                    BaseAgent.java:129
  └─ 循环 step()
       └─ ToolCallAgent.step()                     ToolCallAgent.java:64
            ├─ think()   组装 prompt、调模型（内置 3 次重试）、决定工具调用
            └─ act()     toolCallingManager.executeToolCalls() 执行工具
```

### 1.2 横切逻辑现状盘点（焊死位置）

| 横切功能 | 现在的位置 | 问题 |
| --- | --- | --- |
| SSE activity 事件、死循环/无进展检测 | `runStream` 循环体内（BaseAgent.java:190-215） | 与流程耦合，无法复用到非流式 `run()` |
| 中断摘要 | `buildInterruptedSummary`（BaseAgent.java:332） | 同上 |
| 执行轨迹落库 | `AgentExecutionTraceService.record` 由 AiController 调用，**仅 travel 有**（AiController.java:604） | manus / test / graph worker 无 trace，且记录逻辑散在 controller |
| 工具结果聚合 | `lastToolExecutions` + `GraphWorker` 手工遍历（WorkersNode.java:58） | 每个使用方自己解析 |
| 模型调用重试 | `think()` 内部（ToolCallAgent.java:142-166） | 策略固定，无法按 agent 配置 |
| 沙箱与网络安全 | 散在各 Tool 内部（ToolSandbox / UrlAccessPolicy） | 新工具容易漏接；无统一入口 |
| RAG / 技能 / 记忆注入 | LoveApp、AiController 在 agent 外拼 prompt 字符串 | Love 文本链路不走 ReAct 循环（见 2.2 非目标） |

### 1.3 判断标准

以后每加一个横切能力（审计、限流、敏感词、技能注入、记忆压缩），都不应再修改 `BaseAgent` / `ToolCallAgent` 的代码，只新增 hook 插件。

## 2. 设计目标与非目标

### 2.1 目标

- 定义统一的切点集与 hook 接口，覆盖 run / step / think / act / tool-calls 五层生命周期；
- hook 支持三种能力级别：观察（只读）、转换（修改 prompt / 工具调用计划）、拦截（短路本轮）；
- 单个 hook 异常不拖垮 agent 主流程（观察型吞掉记 warn，拦截型按语义传播）；
- hook 的注册顺序显式可控（order），执行顺序即语义；
- 管线为空时行为与现状完全一致（插桩零行为变化，保证可安全合入）；
- graph 编排内的 `GraphWorker`（继承 ToolCallAgent）自动获得 hook 能力。

### 2.2 非目标

- 不改 Love 文本链路的 RAG 拼接方式（它在 agent 外的 preparation 阶段，不走 ReAct 循环）；
- 第一阶段不迁移任何现有行为（先插桩，后分批迁移）；
- 不把 `SseEmitter` 交给 hook——hook 只产出数据，SSE 发送仍由 BaseAgent 统一负责，避免时序混乱；
- 不动 Planner / Verifier / Aggregator 节点级编排（graph 已有 ActivityEvent 机制，另行演进）；
- 不追求 Channel 多路复用（当前 SSE 单通道够用）。

## 3. 核心概念

### 3.1 切点定义

```text
run            beforeRun ──┐
step 循环       beforeStep │
think           beforeThink ├─ 每一步都触发
act             beforeToolCalls（按计划调用的工具粒度）
                afterToolCalls
think           afterThink │
step            afterStep  ┘
run            afterRun ──┘（含 onError / onInterrupted / onFinish 三个终态回调）
```

对应插桩位置：

| 切点 | 插桩位置 | 典型用途 |
| --- | --- | --- |
| `beforeRun` / `afterRun` | runStream 校验通过后 / finally 前 | 上下文准备、run 级 trace、落库 |
| `beforeStep` / `afterStep` | 循环体 step() 前后 | activity 事件、无进展检测、step 级 trace |
| `beforeThink` / `afterThink` | ToolCallAgent.think() 首尾 | prompt 注入（技能/记忆压缩）、模型调用审计 |
| `beforeToolCalls` / `afterToolCalls` | act() 调 executeToolCalls 前后 | 沙箱校验、限流、工具结果改写、工具级 trace |
| `onError` | runStream catch 块 | 错误上报 |
| `onInterrupted` / `onFinish` | requestStop 触发路径 / 正常结束 | 中断摘要、资源清理 |

### 3.2 AgentRunContext

一次 run 一个实例，随 run 创建销毁，hook 之间通过它传递数据：

```java
public final class AgentRunContext {
    String runId();            // UUID，服务端生成
    String tenantId();         // 可空（程序内调用）
    String agentKey();         // manus / travel / test / super / graph-worker:{role}
    String conversationId();   // 可空
    String userPrompt();
    String agentName();
    int currentStep();
    // hook 间传递数据的属性袋，并发容器
    Map<String, Object> attributes();
    // 只读消息视图、计划中的工具调用等按切点以参数补充传入
}
```

约束：

- hook **不得**持有 per-run 可变状态（保证单例 hook 服务多并发 run）；
- `attributes` 仅用于同一 run 内 hook 间协作（如 trace hook 把 runId 暴露给后续 hook）。

### 3.3 AgentHook 接口

```java
public interface AgentHook {
    /** 唯一标识，用于去重与排障 */
    String id();
    /** 越小越先执行 */
    int order();
    default void beforeRun(AgentRunContext ctx) {}
    default void beforeStep(AgentRunContext ctx) {}
    default void beforeThink(AgentRunContext ctx) {}
    default void afterThink(AgentRunContext ctx, boolean toolCallPlanned) {}
    /** 可对计划调用的工具做修改或拦截（抛 HookAbortException） */
    default void beforeToolCalls(AgentRunContext ctx, List<PlannedToolCall> calls) {}
    default void afterToolCalls(AgentRunContext ctx, List<ToolExecution> executions) {}
    default void afterStep(AgentRunContext ctx, String stepResult) {}
    default void afterRun(AgentRunContext ctx) {}
    default void onError(AgentRunContext ctx, Exception error) {}
    default void onInterrupted(AgentRunContext ctx, String reason) {}
}
```

- `PlannedToolCall`：工具名 + 参数 JSON 的可变视图，转换型 hook 可修改或从列表移除；
- `HookAbortException`：拦截型 hook 主动终止本轮/本次 run 的唯一手段，携带面向用户的原因文案；
- 回调参数尽量复用现有 `BaseAgent.ToolExecution`，不新造重复模型。

### 3.4 管线与注册

```java
@Component
public class AgentHookPipeline {
    // Spring 启动时收集容器内所有 AgentHook Bean，按 order 排序；去重（同 id 覆盖并告警）
    // 提供 fire* 方法：顺序调用，单个观察型 hook 异常 → log.warn 继续；
    // HookAbortException → 向上传播（由插桩点决定终止粒度）
}
```

- 全局 hook：容器内 Bean 自动收集（trace、审计等基础设施类）；
- 运行级追加：`runStream(...)` 增加重载接受临时 hook 列表（用于单次任务的特殊逻辑），run 结束自动摘除；
- hook 数量预期 < 10，不做动态装卸与热更新。

### 3.5 失败隔离与短路语义

| hook 异常类型 | 管线行为 |
| --- | --- |
| 普通异常（观察/转换型） | 记 warn，跳过该 hook 剩余回调，管线继续 |
| `HookAbortException` | 立即停止管线传播；`beforeToolCalls` 中抛出 → 本 step 改为返回「工具调用被拦截：原因」；`beforeRun` 中抛出 → run 直接以受控中断结束（走 onInterrupted） |
| onError/onInterrupted 内异常 | 只记日志，绝不二次抛出 |

## 4. 与现有代码的迁移映射

### 4.1 第一批迁移（验证管线）

| 现有逻辑 | 迁移为 hook | 收益 |
| --- | --- | --- |
| travel 专用 trace（AiController 直调 record） | `ExecutionTraceHook`（order=100，观察型） | manus / test / graph worker 全部获得 trace；controller 只负责传 runId/tenant |
| runStream 内 activity SSE + 死循环/无进展检测（BaseAgent.java:190-215） | `StepMonitoringHook`（order=200，观察型，产出的活动文案放 ctx.attributes 由 BaseAgent 发送） | run() 非流式也获得同等的无进展保护；BaseAgent 循环瘦身 |

### 4.2 明确不迁移

| 现有逻辑 | 理由 |
| --- | --- |
| think() 的 3 次重试 | 属于模型调用策略，与 hook 语义无关；未来如需按 agent 配置再抽策略对象 |
| `completionHandler` 落库 | 是 runStream 的公开接口约定，controller 已依赖；保持不变 |
| RAG/知识库上下文拼接 | 在 LoveApp / AiController preparation 阶段，不经过 ReAct 循环 |
| ToolSandbox / UrlAccessPolicy 在各 Tool 内部的校验 | 涉及所有 Tool 重构，放到后续阶段（见 4.3） |

### 4.3 后续批次（本方案之外，仅占位）

- `ToolGuardHook`：统一沙箱/网络校验入口（阶段 3，需重构各 Tool）；
- `SkillInjectionHook`：markdown 技能仓库加载后，在 beforeThink 注入技能 prompt（依赖技能 markdown 化改造）；
- `MemoryCompressionHook`：beforeThink 检查历史长度，超预算先摘要压缩（design/plans/05 的长期记忆项）。

## 5. 并发与流式时序

- hook 全部在**执行线程**同步调用（当前 step 本就顺序执行），不引入额外并发；
- SSE 事件由 BaseAgent 依据 hook 产出的数据统一发送，事件顺序与现状一致：activity → 最终回答 → [DONE]；
- `requestStop`（看门狗/断连/超时）路径保持现状，`onInterrupted` 只在 requestStop 成功标记后触发一次；
- graph 的 `GraphWorker` 每次 run 由 WorkersNode 构建新实例，天然共享全局管线，无需额外接线。

## 6. 测试与验收

### 单元测试

- 管线按 order 顺序执行、同 id 去重；
- 观察型 hook 抛异常 → 后续 hook 仍执行、主流程不受影响；
- HookAbortException 在 beforeToolCalls 抛出 → 本 step 短路且 reason 进入结果；
- AgentRunContext 隔离：两个并发 run 的 attributes 互不可见；
- beforeToolCalls 修改 PlannedToolCall 后 act() 实际执行的调用计划随之变化。

### 回归验收

- 管线为空时：现有全部测试（ZwxManusTest、WorkerToolFilterTest、VerifierRoutingTest 等）通过，manus SSE 输出与现状逐字节一致；
- 迁移后：travel trace 行为不变；manus/test/graph worker 开始产生 trace 记录；
- 死循环保护迁移后：无进展 5 轮自动停止行为保持（已有行为对照）。

## 7. 推荐落地顺序

1. **阶段 1（最小闭环，零行为变化）**：`AgentHook` / `AgentRunContext` / `HookAbortException` / `AgentHookPipeline` + BaseAgent、ToolCallAgent 全切点插桩 + 管线单测；插桩点接空管线，行为不变合入。
2. **阶段 2（迁移验证）**：实现 `ExecutionTraceHook`（通用化 trace，travel 既有调用点切换/共存）与 `StepMonitoringHook`（activity 与无进展检测迁出 runStream），回归 manus / travel / graph。
3. **阶段 3（能力扩展，另行立项）**：ToolGuardHook 统一沙箱入口；技能 markdown 化 + SkillInjectionHook；记忆压缩 hook。

不要在阶段 1 顺手迁移任何现有逻辑——先让插桩本身证明「空管线 = 零行为变化」，再分批搬运。
