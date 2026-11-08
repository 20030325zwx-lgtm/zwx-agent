# 会话摘要：编排模式化三档——单循环档上线（2026-11-08 上午）

## 需求与决策

承接 2026-11-07 agent-loop-upgrade 会话的 P2：用户确认 Anthropic「反对过早多 agent 化」
对本项目成立（简单工作任务被强制走四阶段图，实测 llm_calls=3-4、延迟 18-32s 交付一句话），
拍板落地「直答 / 单循环 / 图编排」三档。实施依据见
`docs/design/2026-11-07-agent-loop-upgrade.md` 的「P2 落地记录」节。

## 改动（后端）

- `DirectNode`（graph 包，新增）：单循环档核心，单拓扑图 `START → direct → END`。
  复用 ToolCallAgent（全工具、8 步上限、hook 管线、确认挂起、幂等账本）；思考文本经
  thinkTextSink → answerChunkSink 实时流式（叙述即回答）；确认挂起输出 PENDING_CONFIRMATION
  与图编排同语义；系统提示词面向用户（禁止元话语）。
- `WorkersNode`：`activeWorkers` 静态化为 `ACTIVE_WORKERS` + registerWorker/unregisterWorker
  静态方法——DirectNode 共享取消通道，用户停止请求覆盖两种拓扑；`WORKER_MAX_STEPS` 转包内可见。
- `ManusGraphOrchestrator`：`buildDirectGraph`（复用同一 WorkersNode 实例承载注册表）、
  runStream 按 `request.orchestration()` 选拓扑、`GraphState.ORCHESTRATION` 键 + inputs 注入、
  completedNode（single → NODE_DIRECT）、nextNode（direct 自恢复）、execute() 的
  FINAL_ANSWER 捕获加 NODE_DIRECT 分支；开关 `app.agent.single-loop.enabled`。
- `ManusRunRequest` 加 `orchestration` 字段（17 参 compat 构造器保留，默认 null=图编排）。
- `ManusAgentController.chat`：意图路由 complexity ≤0.45 → 单循环；**意图路由不可用时短消息
  （≤80 字）启发式直通**（DashScope qwen-turbo 限流为常态，实测 400/429——兜底必要）；
  工作区/带图/高复杂度 → 图编排；prefaceActivity 透明标注分档原因。
- `ManusAgentController.resumeManusRun`：按 checkpoint.lastNode==NODE_DIRECT 识别单循环恢复
  （无需新 DB 列）。
- `AgentMetrics.orchestrationSingleLoop` 计数器。

## 验证

- 单测 5/5（DirectNodeTest：流式回答/停止早退/提示词纪律/路由与检查点归属/事件常量）；
  受影响图测试 38/38 全绿（含 ResumeRouting/PlanGate/Orchestrator 既有回归）。
- 真实三轮（本地）：①首验发现 direct 走了 DashScope（buildDirectGraph 漏传 per-run 模型）→ 修；
  ②次验回答成功但 status=INTERRUPTED（execute() 漏读 direct 的 FINAL_ANSWER）→ 修；
  ③终验 **COMPLETED / last_node=direct / llm_calls=1 / 6987ms**，回答正确流式落库，
  事件日志 run/start→step/start→step/end→run/end，v2 快照落库。
- 对照结论：同档简单任务 **llm_calls 3-4 → 1，延迟 18-32s → 5.6-7s**。

## 过程中的 bug（已修，教训入档）

1. **图节点必须 per-run 构建**：buildDirectGraph 初版用了 orchestrator 默认 chatModel，
   用户选的 GPT-5.6 被静默替换成 DashScope qwen（400）——与 buildGraph(request.chatModel()) 的
   既有模式对齐后修复。
2. **拓扑感知的状态读取**：execute() 对 FINAL_ANSWER 的读取按 output.node() 硬编码 aggregator，
   新增拓扑必须同步扩展——completedNode 同理（ORCHESTRATION 键是为此打的标记）。

## 遗留

- 意图路由供应商修复（DashScope 限流中；Jev 未充值）——当前靠短消息直通兜底，
  意图路由恢复后复杂度分档自动生效。
- 单循环档 worker 无 verifier 自检：低复杂度任务可接受；若反馈质量下降，可在
  DirectNode 加「可选自检」（对话尾轮让模型自评一次），暂不做。
- 观察项：`agent.orchestration{mode=single}` 计数器 vs 总 run 数的分档比例；
  短消息直通误判率（复杂任务被直通后的用户反馈）。

## 注意

- 开关回退：`app.agent.single-loop.enabled=false` 即全量回到图编排（零代码回滚）。
- 直接查分档：`agent_run.last_node='direct'` 即单循环档 run。
- 本轮含 push + 线上部署（用户要求）；上一批（checkpoint 融合治理）为 0.7.27。
