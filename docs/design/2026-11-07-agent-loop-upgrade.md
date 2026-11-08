# Agent 循环升级：对照市面方案的差距分析与 P0 实施（2026-11-07）

## 背景

对照市面第一梯队 agent 循环方案（Claude Code / Agent SDK、OpenAI Agents SDK、LangGraph）审视当前自研
ReAct 循环。结论：**自研循环方向正确、且多项生产底座（hook 管线、五档权限、幂等账本、run 恢复、配额、
轨迹）反而优于开源 demo**；差距集中在 4 个具体点，按优先级增量升级，不推倒重来。

## 差距盘点（2026-11-07 核实后的真实状态）

先澄清一个容易误判的点：**最终回答已经是 token 级流式**——`AggregatorNode.apply` 用 `.stream().content()`
并把增量经 `RunContext.answerChunkSink` 写入 `RunStream`（`message` 事件，前端 `onmessage` 逐块追加）；
批 2 闲聊短路（`ManusAgentController.casualChat`）也是全程流式。真正的差距如下：

| # | 差距 | 现状 | 市面做法 | 优先级 |
|---|---|---|---|---|
| 1 | **干活阶段空白期**：worker `think()` 阻塞 `.call()`（`ToolCallAgent.java`），一个 think 轮次 10-40s，期间用户看不到任何字 | plan 事件后到第一条 activity 之间全空白 | 中间 assistant 文本实时流式可见（Claude Code 亦然） | **P0（本次）** |
| 2 | 终止策略：`maxSteps=10` + 5 分钟硬时限 | 固定步数 | 上下文 token 预算 + 配额制（配额底座 `RunQuotaService` 已有） | P1 |
| 3 | 编排哲学：planner→workers→verifier→aggregator 固定图是唯一路径 | 每请求 4+ 次模型调用起步，简单任务也全链跑 | 单主循环默认 + 复杂任务才派子代理；verifier（LLM 自评）/aggregator 做成可选 | P2 |
| 4 | 无供应商 failover：think 重试打同一 provider | 中转站 429 时 3 次重试全部打同一通道（11-07 实测踩坑） | 主备模型自动切换 | P1 |

另有小项：单步内多个 tool call 串行执行（Spring AI `ToolCallingManager` 默认顺序）。

## P0 设计：worker 思考阶段流式化

### 目标

worker `think()` 从阻塞 `.call()` 换为 `.stream()`，文本增量实时经 SSE 推给前端；
工具调用 delta 在服务端聚合，**聚合后的行为与 `.call()` 路径完全一致**（消息历史、hook、
确认、账本、重试语义零变化）。sink 未注入时自动回落阻塞路径，保证程序内调用/评测/定时任务
零行为变化。

### 技术前提（源码已核实）

- Spring AI 1.0.0 关闭内置工具执行后，`.stream()` 吐**原始 delta**：工具调用按
  首块（id+name）+ 后续块（arguments 片段）下发，框架不聚合。
- `MessageAggregator` / `ChatClientMessageAggregator` **只聚合文本与 usage，不合并工具调用**
  （v1.0.0 源码核实），且 advisor 链是 pass-through（delta 能到达消费方，计量不受影响，
  `TokenUsageAdvisor` 同时实现 `CallAdvisor`/`StreamAdvisor`）。
- 结论：需要自研聚合器（约 60 行），负责 文本拼接 + 工具调用合并 + usage 累计。

### 服务端改动

1. **新增 `agent/core/ThinkStreamAggregator`**
   - 输入 `Flux<ChatResponse>` + `Consumer<String> textDelta`，阻塞消费（worker 本就同步执行）。
   - 工具调用合并规则：id 非空 → 同 id 追加 arguments，否则新条目；id 空 → 追加到最后一条
     （OpenAI/DashScope 流式按调用顺序分块下发，实践为顺序块而非交错块）。
   - 输出聚合 `ChatResponse`（AssistantMessage 携带完整 text + toolCalls），行为等价 `.call()` 返回值。
   - 每个 chunk 检查 `stopReason`：请求停止时立即中止订阅。

2. **`ToolCallAgent`**
   - 新增 `Consumer<String> thinkTextSink`（volatile，null = 阻塞路径）。
   - `think()`：sink 存在 → 流式路径；聚合结果处理与原路径共用（空 toolCalls → 记录消息返回 false；
     否则 `lastStepUsedTool=true` 交给 `act()`）。
   - 重试语义保留（3 次指数退避）；重试前向前端发一条诚实标记增量「（模型连接中断，重试中…）」。

3. **事件通道**
   - `RunContext` 增加 `Consumer<ThinkDeltaEvent> thinkDeltaSink`（新 record：workerKey/agent/text）。
   - `ManusGraphOrchestrator` 组装 RunContext 时接线到 `stream.send("think-delta", json)`，
     开关 `app.agent.worker-think-stream`（默认开）。
   - **合流器**（WorkersNode 内）：增量按 ≥120 字符或 250ms 刷一条事件，避免逐 token 打满
     SSE 历史（`trimHistory` 对 CopyOnWriteArrayList 是 O(n) 摘头）；delta 走正常历史事件，
     断线重连可无缺口重放（重复仅限 lastEventId 之后的新事件，前端天然幂等）。

4. **WorkersNode/GraphWorker**：为每个 worker 注入 sink（携带 workerKey 与角色名），
   `runWorker` 结束时 flush 残余缓冲。

### 前端改动（SuperAgent.vue）

- 监听 `think-delta`：按 answer 维护「正在思考」活区（agent 名 + 流式文本，滚动最新 400 字）。
- 同一 worker 的 `activity` 事件到达或 run 结束时，活区收起清空（思考片段已由 activity 语义覆盖）。
- 重连恢复：think-delta 为正常历史事件，重放自动重建活区。

### 明确不做

- 不改消息历史结构与 hook 切点（聚合结果与 `.call()` 等价）。
- planner（输出 JSON）不做流式；verifier 同理。
- reasoning 模型的思维链（reasoning_content）展示：后续单独评估。

## 验收

- 单测：聚合器（分片/多调用/空 id 续块/usage）7 项 + 流式 think 路径（stub ChatModel 发 delta 块）4 项，11/11 全绿。
- 回归：全量 144 个测试类，仅 3 个已知基线失败（ToolGuardHookTest / MediaJobSchedulerTest /
  PgVectorVectorStoreConfigTest，DashScope 欠费所致，与本轮无关）。
- 真实验证（三轮运行，GPT-5.6 Terra）：
  - 后端 `[think-delta]` 日志：68 字结论拆成 6 条增量实时下发（1+9+18+14+17+9=68），
    250ms 合流窗生效，`stream.send` 无失败；
  - 前端轨迹：三轮均出现「思考片段：…」折叠条目，文本与后端增量总和精确一致；
  - 完整链路（意图→规划→worker 流式思考→工具调用→质检→返工→交付）行为与改造前一致。
- 已知观感限制：模型单轮思考输出较短时（<120 字且流间隔 <250ms），增量会攒到该轮
  think 结束的冲刷信号统一下发（每轮结束必冲，不是等 worker 整轮结束）；长思考
  （reasoning 模型/长输出）逐窗实时可见。think-live 活区窗口与思考时长等长，
  短思考时视觉上接近「思考片段」直接折叠入轨。

## 实施备注（2026-11-07 深夜落盘）

- 落地文件：`agent/core/ThinkStreamAggregator`（新增）、`agent/graph/ThinkDeltaEvent`（新增）、
  `agent/graph/ThinkDeltaCoalescer`（新增）、`ToolCallAgent`（think 双路径 + 重试标记 + chatSpec
  空系统提示词防御）、`RunContext`（第 10 个字段 thinkDeltaSink + wither）、`WorkersNode`
  （GraphWorker 注入合流器、runWorker 冲刷残余）、`ManusGraphOrchestrator`（开关
  `app.agent.worker-think-stream` 默认开 + sink 接线）、前端 `SuperAgent.vue`（think-delta
  监听 + 活区/折叠 + 各收尾清理）与 `AgentTrace.vue`（.think-live 活区样式）。
- 顺手修复的既有隐患：`.system(null)` 会在 DefaultChatClient 直接抛
  IllegalArgumentException（chatSpec 现在跳过空系统提示词）；`target/classes` 里
  11-05 遗留的 macOS 复制类 `Foo 2.class/3.class` 垃圾（已清，src 无残留）。
- 调试经验：IAB 浏览器 evaluate 跑在隔离 JS 世界，`window.*` 写入与页面主世界不互通，
  DOM 读取共享——靠 window 变量给页面装探针无效，观测后端事件应走后端日志或 REST 事件端点
  （注意 RunStream 完成 120s 后即从内存清退，事件端点只对活跃/近期 run 有效）。

## P1/P2 后续路线

- P1-a 步数上限 → token 预算：挂 `RunQuotaService`，per-run token 预算 + 上下文占用估算。
- P1-b 模型 failover：`DynamicChatModelFactory` 配置备选链，think 连续失败降级备用模型。
- P2 编排模式化：意图路由已有（Qwen/Jev + 闲聊短路），扩展为「直答 / 单循环 / 图编排」三档；
  verifier 与 aggregator 变为图编排档位的可选项。

## P2 落地记录（2026-11-08）：编排模式化三档

**直答（已有闲聊短路）→ 单循环（新增 DirectNode，低复杂度默认）→ 图编排（保留）**：

- `DirectNode`（graph 包）：单拓扑图 `START → direct → END`。一个主循环 ReActAgent
  （复用 ToolCallAgent + hook 管线 + 确认挂起 + 幂等账本）直接对用户负责；思考文本
  （含工具调用前的叙述）经 thinkTextSink → answerChunkSink 实时流式——叙述即回答
  （Claude Code 式）；worker 注册表与 WorkersNode 共享（`ACTIVE_WORKERS` 静态化），
  停止请求覆盖两种拓扑。
- 路由（ManusAgentController.chat）：意图路由命中且 `complexity ≤ 0.45`（0-2 语义）
  → 单循环；**意图路由不可用（DashScope 限流为常态）时短消息（≤80 字）启发式直通**；
  工作区请求/带图/高复杂度 → 图编排。开关 `app.agent.single-loop.enabled` +
  `max-complexity` + `fallback-short-message` + `max-chars`。
- 恢复：checkpoint.lastNode=NODE_DIRECT 的 run 按 `resumeNode → NODE_DIRECT` 自恢复
  （重跑本节点，副作用由幂等账本回放）；无需新增 DB 列。
- 观测：`agent.orchestration{mode=single}` 计数器 + prefaceActivity 向用户透明标注分档原因。
- 实测（本地，同档任务对照）：**llm_calls 3-4 → 1，延迟 18-32s → 5.6-7s**。
- 过程中修的两个真 bug：① buildDirectGraph 漏传 per-run 模型（用户选的 GPT-5.6 被替换成
  DashScope qwen 直报 400——图节点必须逐 run 构建的原因所在）；② execute() 只在 aggregator
  节点读 FINAL_ANSWER，direct 节点回答捕获不到导致 status 恒 INTERRUPTED。
- 已知边界：意图路由的 complexity 是 0-2 语义（0=单轮可答），阈值 0.45 只放行单轮任务——
  多步骤但线性的任务仍走图编排；若要进一步扩单循环覆盖面，调高
  `app.agent.single-loop.max-complexity` 即可（配合评测观察）。
