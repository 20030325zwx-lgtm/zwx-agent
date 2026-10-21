# DeepSeek Harness vs yu-ai-agent：Agent 架构逐点对比

- 对比对象：[deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)（本地路径 `/Users/zhuwenxuan/Documents/code/office-demo/src/main/resources/templates/deepseek-harness`，下称 dsh）
- 我方项目：yu-ai-agent（Spring Boot + Spring AI + DashScope）
- 写作日期：2026-09-28
- 性质：架构学习笔记 + 改造路线参考。每个对比点给出：他们的做法 → 我们的现状 → 差距含义 → 借鉴建议。

---

## 总览

dsh 的两条设计公理：

1. **Everything is a plugin**（基于 Cordis 框架）：模型适配器、工具注册表、session 日志、审批策略、**agent loop 本身**都是插件，可被配置（`cordis.patch.yml`）逐行替换，没有需要改源码的"特权核心"。
2. **Model-visible means logged**：凡是会进入模型请求的内容，必须能从 append-only 会话日志重放出来，并有运行时不变式强制校验。消息历史不是"存出来的状态"，而是日志的**投影**。

我们的项目是经典教学式 ReAct 结构：`BaseAgent`（循环/状态/SSE）→ `ReActAgent`（think/act）→ `ToolCallAgent`（Spring AI 工具调用）→ `ZwxManus`，外加一条固定编排的 graph（Planner→Workers→Verifier→Aggregator），hook 用全局静态管线。骨架正确，差距主要在**状态的持久化归属**和**扩展点的语义等级**。

| # | 对比点 | dsh | yu-ai-agent | 差距等级 |
|---|--------|-----|-------------|---------|
| 1 | 状态归属 | append-only 事件日志，历史是投影 | 内存 `List<Message>` + SSE 即发即弃 | ★★★ 结构性 |
| 2 | 扩展模型 | 插件树 + waterfall 事件 | 继承链 + 全局静态 hook | ★★ |
| 3 | 工具管线 | prepare→pre-execute→execute→validate/render→post-execute | `executeToolCalls` 一把执行 + 前后两刀 hook | ★★ |
| 4 | 终止与取消 | typed 停止原因 + 取消一致性回放 | 字符串 reason + watchdog/熔断（细节更好） | ★★ |
| 5 | Prompt 组装 | section 注册制 + 动态上下文快照 | 单个 systemPrompt 字符串 | ★ |
| 6 | 上下文压缩 | token 压力触发 + 日志代际替换 | MemoryCompressionHook（时机驱动） | ★★ |
| 7 | 子 agent / 并发 / LLM seam | 独立 session + 深度上限 + 并行工具池 + 适配器注册表 | 固定 graph 编排 + 串行工具 + 绑死 ChatClient | ★★ |

---

## 1. 状态归属：append-only 事件日志 vs 内存消息列表

### 他们怎么做

- 核心：`packages/core/session`。一个 Session 是一条**只追加**的事件流，每条事件 `{type, seq, time, data快照}`，append 时同步校验可 JSON 化并拒绝重入。
- 事件词汇表：`packages/core/session/src/known-event-types.ts`（由脚本生成，60+ 种）：`turn/start|end`、`step/start|end`、`user/message`、`system/message`、`assistant/message|attempt`、`tool/call|result`、`request/header|context`、`approval/asked|decided`、`compaction/*`、`llm/retry*`、`todo/write`、`subagent/*`、`goal/change`……
- 持久化：JSONL（`session.vN.jsonl[.zstd]`），带版本迁移链；崩溃恢复由 `repair.ts` 补合成 `turn/end`，只修复"未封口的尾部"。
- **消息历史是投影**：`deriveMessages()` 沿 SurfaceManager 维护的"消息产出节点"序列增量构建，返回 deep-frozen 共享数组。compaction 用 generation 重建。模型每次请求的 history 都从日志推导并冻结，保证可重放。
- 失败的模型调用也落日志（`assistant/attempt`），用于审计与遥测，但不进模型历史。
- fork/resume/转写/遥测全部从同一份日志派生，没有第二份真相。

主循环骨架（`packages/core/agent-loop/src/agent.ts:253`）：

```ts
async run() { while (await this.turn()) {} }
```

turn 内层逐步执行 step，`finally` 中补 `turn/end`；每个模型请求、每次工具调用、每次重试都在日志里留下成对事件。

### 我们的现状

- `BaseAgent.messageList`（内存 `List<Message>`）是唯一运行态；`run()` 的过程数据在局部 `List<String> results` 里，方法返回后即丢。
- SSE 事件（activity/generation-error）即发即弃；客户端断线重连无法恢复过程。
- 持久化只发生在**最终答案**（completionHandler → 会话存储），中间过程（模型原始响应、工具原始返回、重试、拦截）没有留痕。
- 已有的 `ExecutionTraceHook` 做了部分 trace，但输出目的地与模型历史无关，不构成"可重放真相"。

### 差距含义

没有事件日志，就无法做：断线续跑、任意轮次回放调试、审计（为什么这轮调了这个工具）、fork 分支、以及"多端一致的过程视图"。这些是 dsh 所有上层能力的地基。

### 借鉴建议（Java 落地）

1. 新建 `RunEventLog`：每次 run 打开一个 `runs/<conversationId>/<runId>.jsonl`，事件 `{"seq":n,"type":"...","time":...,"data":{...}}` 逐行追加、只写不改。
2. 第一批事件类型够用即可：`run/start|end`（data 带 typed stopReason）、`step/start|end`、`user/message`、`assistant/message`、`assistant/attempt`（失败重试）、`tool/call|result`、`hook/block`。
3. `messageList` 降级为"投影缓存"：启动续跑时从日志重建，而不是依赖内存。
4. 不变式先弱后强：先保证"assistant 最终消息与 tool 结果都在日志里"，再追求 dsh 级别的"model-visible means logged"。

---

## 2. 扩展模型：插件 + waterfall vs 继承 + 全局静态 hook

### 他们怎么做

- 一切能力是挂到 Cordis 上下文（`ctx`）上的服务：`ctx.tools`、`ctx.llm`、`ctx.sessions`、`ctx.agents`、`ctx.fs`、`ctx.sandbox`……每个 key 都有 Service Definition（接口）/ Provider（实现）/ Consumer（使用者）三角色，即"能力缝（seam）"。换一个 Provider（如把 fs/subprocess 指向远程沙箱）整个产品行为跟着变。
- 扩展点是事件，分三类：
  - **持久事件**（session log 里的）
  - **活性事件**（`agent/*`，观察/拦截进行中的工作）
  - **waterfall**（`agent/pre-step`、`agent/request`、`llm/stream`、`tools/pre|execute|post-execute`、`system-prompt/assemble`）：监听器链式调用 `next()`，前面的监听器可以**改写或拒绝**后面的输入，策略层层叠加。
- agent loop 自己也是插件（`ctx.agentLoop`），可整体替换。

### 我们的现状

- `AgentHooks.pipeline()` 是全局静态单例，切点：beforeRun / beforeStep / afterStep / beforeThink / afterThink / beforeToolCalls / afterToolCalls / onFinish / onError / onInterrupted / afterRun。
- 已具备 waterfall 的雏形：`beforeToolCalls` 可改写 `PlannedToolCall` 列表或抛 `HookAbortException` 拒绝；`afterToolCalls` 可原地修改结果。
- 问题：(a) 全局静态 → 无法按 agent/会话/租户组装不同 hook 集；(b) 无优先级委托语义，hook 之间不能叠加策略；(c) 无返回值分级（allow/deny/ask），只有"改"和"抛异常"两态。

### 差距含义

waterfall 与静态管线在功能演示上等价，但在**组合**上分叉：dsh 可以给"一个会话"换一套能力（agent preset + isolate realm），可以叠三层策略（公司合规 → 租户策略 → 会话级），每层独立测试。我们加策略只能往同一个类里堆。

### 借鉴建议

1. 把 `AgentHooks.pipeline()` 从静态改为 **per-run 实例**：`AgentRunContext` 创建时组装链（Spring 注入所有 `AgentHook` bean，按 `@Order` 排序，逐个包成委托链）。
2. hook 返回值从 void/exception 升级为密封结果：`allow` / `deny(reason)` / `ask(approvalId)`（见第 3 点）/ `rewrite(payload)`。
3. 保留现有切点命名，逐步把 before/after 双刀收敛为单 waterfall（`next()` 语义）。

---

## 3. 工具执行管线：五段式 staged 管线

### 他们怎么做

`packages/core/tools` 的 `ToolRuntime`，每次工具调用走五段：

1. **prepare**：参数 lossless-JSON 快照 + deep-freeze（之后任何环节都不能偷改入参；PTC 场景外的模型直调在此确定性拒绝）。
2. **pre-execute**：`tools/pre-execute` waterfall 产出 allow / deny / cancel / **ask**；`ask` 交给 `ctx.get('approval')` 审批服务（可选缝，事件 `approval/asked|decided` 落日志）；随后过 **guard** 层——`tools.guard()` 注册的拒绝器是**单调的**：返回 reason 即拒绝，任何 guard 不能强行放行已被别处拒绝的调用。
3. **execute**：`tools/execute` waterfall 挂超时/重试 wrapper；工具实现 `execute(args, exec)` 返回 canonical JSON value，caller 的取消信号被 fuse 回来。
4. **validate + render**：输出按工具自带的 `output.schema` 校验，再由 `render(args, value)` 渲染成内容块（模型可见文本与机器值分离）。
5. **post-execute**：可替换/拦截结果 → `tool/result` 事件落日志。

并发调度（`packages/core/agent-loop/src/tool-calls.ts`）：`isConcurrencySafe` 为 true 的相邻调用进有界并行池，否则 exclusive 成 barrier；结果**按模型给出的顺序**提交，保证回放一致。

工具定义（`defineTool`，`packages/core/tools/src/schema.ts:554`）带 name/description/parameters schema/output schema/render/timeoutMs/isConcurrencySafe，一处声明、处处受管。

### 我们的现状

- 工具是 Spring AI `ToolCallback` 注解类（`tools/` 下 9 个），参数校验靠 Spring AI 内建。
- 执行：`ToolCallingManager.executeToolCalls(prompt, response)` 一把执行完所有调用（串行），结果为字符串。
- 管控：`ToolGuardHook`（基于 `ToolGuardPolicy`/`ToolSandbox`/`UrlAccessPolicy`）只有 deny 一档；`ToolResultTruncationHook` 截断超长结果；被拦截调用用 `recordBlockedToolCalls` 把拒绝原因成对写回历史（这个做法与 dsh"合成结果"思路一致，值得肯定）。
- 缺：ask（人工审批）档位、输出 schema 校验、渲染层、并发调度、超时 per-tool 声明。

### 差距含义

五段管线的价值不在于"多几步"，而在于**每个横切关注点有了标准挂点**：审批、沙箱、超时、重试、渲染都是管线上的一个 listener，而不是散在工具实现里。新增一个企业管控需求 = 注册一个 guard，零侵入。

### 借鉴建议

1. 先补 **ask 档位**：`ToolGuardHook` 增加 `ToolDecision.ask(approvalId)`，走已有的 conversation 通道问用户，回令落 `hook/block` 或新的 `approval/decided` 事件。
2. 给工具接口加 `timeoutMs` 与 `concurrencySafe` 默认 false；串行可以先不动，挂点先留出来。
3. 输出截断从 hook 移到统一 `render` 步骤（hook 只做策略，不做格式化）。

---

## 4. 终止与取消：typed 停止原因 + 取消一致性

### 他们怎么做

- turn 终止原因是 typed union：`completed | max-tokens | aborted | error | blocked`，落 `turn/end` 事件；`max-tokens` 是 sticky 的（后续 step 不能把 turn 结果"降级"成 completed）。
- 取消：每个 phase 一个 AbortController；abort 后**已启动的 tool call 照常执行并提交结果**，未启动的写入合成错误 result（`appendSkippedToolCall`）——保证任何时刻日志都是完整可回放的，不会出现"模型发了 tool call 但日志里没有对应 result"的悬空。
- 重试：流失败进 `agent/request-error` waterfall；默认抛错，`llm-retry` 插件返回 `{kind:'retry'}` 并先落 `llm/retry` + `llm/retry-started` 事件再重试。

### 我们的现状

- `stopReason` 是 String，终止路径覆盖得**比 dsh 细**：maxSteps、5 分钟总时限 + 每步 watchdog、同一工具同结果 53 次熔断、连续 5 轮无新结果熔断、SSE 断连停止。这些防呆熔断是 dsh 没有的，保留。
- 模型调用重试：`think()` 里手写 3 次指数退避，失败后 `lastThinkFailed` 显式中止，不把错误文案写进记忆（思路正确）。
- 缺：reason 无类型（前端无法可靠分派 UI）；取消发生在工具执行中时没有"合成 result"保证消息成对；重试不落事件（第 1 点的子问题）。

### 借鉴建议

1. `stopReason` 改 enum：`COMPLETED / MAX_STEPS / DEADLINE / NO_PROGRESS / DUPLICATE_TOOL / BLOCKED / CLIENT_DISCONNECT / ERROR`，随 `run/end` 事件落日志。
2. `recordBlockedToolCalls` 的手法推广：任何"计划了但没执行"的工具调用都以合成 result 收尾（cancel/abort 同理），维持 Spring AI 协议的成对不变式。

---

## 5. Prompt 组装：section 注册制 + 动态上下文快照

### 他们怎么做

- `packages/core/system-prompt`：prompt = 注册的 section 列表 + contexts + tools + variables。每个 section `{name, order, text|provider, complete}`，order 槽位集中分配（identity 最前，persona 最后），同 order 按名字排序，组装再过 `system-prompt/assemble` waterfall。
- 关键决策：**动态运行时上下文（时间、沙箱模式、审批策略、子 agent 可用性）不走 system prompt**，而是渲染成 durable 的 user-role 快照注入消息历史；新快照 supersedes 旧快照。好处：system prompt 保持稳定可缓存（prompt cache 友好），动态事实有日志时间线。

### 我们的现状

- 单个 `systemPrompt` 字符串 + `nextStepPrompt`（首次 step 后不再追加）。
- 记忆注入有 `MemoryInjectionHook`（转换 messageHistory），方向接近 dsh 的"上下文注入"，但无 supersedes 语义（旧快照不失效）。

### 差距含义

小项目里 system prompt 拼接问题不大；一旦要做 prompt 缓存省钱、或多租户按身份改人格，section 注册制才能避免字符串手工拼接失控。

### 借鉴建议

1. 引入 `PromptSection` 列表（`identity / tools / policies / persona` 四段起步），`systemPrompt()` 由列表渲染，替代手拼。
2. 动态上下文（当前时间、数据库连接状态等）改为注入 user 快照消息并带 `supersedes` 标记。

---

## 6. 上下文压缩：token 压力触发 + 日志代际

### 他们怎么做

- `packages/compaction/compaction-basic`：触发挂 `agent/pre-step`（token 用量 > contextWindow × 0.8）与 `agent/request-error`（context overflow 自动恢复）。
- 做法：把旧消息跨度 replace 成一条摘要消息；**旧事件的 seq 保留在日志里**（append-only 不改写），投影按新 generation 重建，模型只看得到摘要。`compaction/start|summary|end|prune` 全部落日志。

### 我们的现状

- `MemoryCompressionHook`（beforeStep 时机压缩 messageHistory）。压缩是"改写内存列表"，没有代际概念，也没有落事件。

### 差距含义

没有代际，压缩与日志（第 1 点）无法协同：无法回答"模型这轮为什么看不到那条消息"。压缩本身我们已有，缺的是可解释性与触发正确性（按 token 压力而非固定轮次）。

### 借鉴建议

1. 压缩触发条件改 token 估算（字符数/4 起步即可）超阈值触发，挂在 beforeStep。
2. 压缩动作落 `compaction/start|summary|end` 事件（依赖第 1 点的日志），被压缩的原始内容留在日志不删。

---

## 7. 子 agent、工具并发、LLM seam

### 他们怎么做

- **子 agent**（`packages/subagent`）：每个子 agent = 独立 Session + 独立 Agent；深度记账（父深度+1，持久化且单调）+ 深度上限；`toolFilter`/persona 裁剪工具面；父子通信走带 lineage 授权的消息；spawn driver 可插（in-process / out-of-process / 对接外部产品）。
- **LLM seam**（`packages/llm/llm`）：`ctx.llm.registerAdapter(['deepseek'], adapter)`；适配器唯一必须方法是 `stream(options): AsyncIterable<StreamChunk>`；`llm/stream` waterfall 可拦截流（重放/路由/遥测）。
- 我们对比：graph 包是固定 Planner→Workers→Verifier 编排（类似 workflow，不是动态 subagent）；工具全串行；LLM 绑死 Spring AI ChatClient + DashScope options。

### 差距含义

固定 graph 适合确定性流程，subagent 适合开放式委派，两者定位不同不必互斥。串行工具在"多路并行检索"场景浪费时延。LLM 绑定在单一 Spring 技术栈下可接受，但接口处留缝（未来混用 vLLM 自部署模型）会省大事。

### 借鉴建议

1. 给"只读、无副作用"的工具标 `concurrencySafe=true`，在 `act()` 里对相邻 safe 调用做 `CompletableFuture` 有界并行，结果按原顺序回填。
2. subagent 暂缓；若做，最小版 = 新线程 + 独立 messageList + 工具子集 + 深度上限常量。
3. LLM 调用收敛到一个 `LlmGateway` 接口后面（现在散在 `think()` 直接调 ChatClient），留出换模型/换供应商的缝。

---

## 改造路线（建议顺序）

1. **RunEventLog 事件日志**（第 1 点）——地基，其他改造的产物都往这里落。
2. **typed stopReason + 合成 result**（第 4 点）——小改动，立刻提升前端与可回放性。
3. **hook 管线 per-run 实例化 + allow/deny/ask 分级**（第 2、3 点）。
4. **token 压力压缩 + compaction 事件**（第 6 点）。
5. **PromptSection 注册制**（第 5 点）。
6. **只读工具并行**（第 7 点）。

每步都可独立交付、独立验证；不要求一步到位 Cordis 化——Spring 的 DI 容器已经能承担"插件树"的大部分职责，缺的只是事件化与 waterfall 语义。

## 参考文件索引（dsh 侧）

| 主题 | 路径 |
|------|------|
| 架构总览 | `docs/architecture.md` |
| 主循环 | `packages/core/agent-loop/src/agent.ts` |
| 工具并发 | `packages/core/agent-loop/src/tool-calls.ts` |
| 工具定义/管线 | `packages/core/tools/src/schema.ts`、`index.ts` |
| 事件词汇表 | `packages/core/session/src/known-event-types.ts` |
| 投影 | `packages/core/session/src/surface.ts` |
| 压缩 | `packages/compaction/compaction-basic/` |
| 子 agent | `packages/subagent/subagent/src/continuation.ts` |
| LLM seam | `packages/llm/llm/src/index.ts` |
