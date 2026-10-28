已被 `session-2026-10-28-permission-modes-eval-trajectory.md` 取代（本文件保留历史）

# 会话交接：依赖计划与证据验收（P0 三项）（2026-10-28）

## 追加改动二（10-28 晚）：P0 收尾 + P1 评测闭环

- **计划先对齐后执行**（EvoFlow Plan 模式核心）：planner 后新增条件边——多步计划路由到 `plan_gate` 空节点直接挂起（run 状态 INTERRUPTED、`verification_status='plan_pending'`、发 `plan-proposed` SSE 事件），工作台显示计划卡片（查看计划/开始执行）；授权走既有 resume 链路（checkpoint lastNode=planner → resume 到 workers，不会重过门）；单步任务不设门直接执行。恢复时 plan_pending 状态不再带入图状态。
- **revise_steps 持久化**：V31 迁移加 `agent_run.revise_steps JSONB`，checkpoint 读写贯通，恢复后仍按质检点名只重跑相关步骤。
- **时间线状态色**：执行中蓝、暂停黄、失败红。
- **任务级端到端评测**：`run_task_eval.py` + `task-eval-v1.json`，覆盖闲聊直达/知识库单步/多步依赖三路径；自动代用户授权计划门；SSE 按 spec 解析（多行 data 合并）。实测：task-003 2.4s ✅、task-001 27s ✅（验收 pass）、task-002 117s ✅（计划门触发、s2 依赖 s1、产出 .md 文件、验收 unverified 为一轮返工后的设计行为）。凭据读 `frontend/.env.local`（不回显），结果 JSON 已 gitignore。
- 遗留：Goal 后台模式、任务经验复用、计划修订对话（当前修订=直接发新消息重新规划）、暗色主题等 P1/P2 项未做。

## 追加改动三（10-28 深夜）：结构整理（零行为变更）

- 前端：时间线从 ChatRoom 拆为独立组件 `AgentTrace.vue`（状态行/步骤条目/折叠逻辑与样式内聚），ChatRoom 回归纯消息渲染；删除无引用的 `hydrateRagJudgments`；清理迁移后的残留样式与 lucide 导入。
- 后端：`WorkersNode` 选择性返工的统计逻辑重写为 `reviseTargets` 预过滤 + 计数变量，消除内联 stream 绕计算。
- 清理 Finder 副本垃圾文件（`2026-10-25-jev-intent-routing 2.md`、`run_intent_gate 2.py`，均为改动前旧快照）。
- 验证：mvn test 331 通过、打包重启 health ok、前端构建通过、评测冒烟 task-001（27s 验收 pass）与 task-003（闲聊直达）全过后才提交。

## 追加改动：时间线折叠与回答复制

- ChatRoom：时间线在执行中默认展开、结束后折叠为「状态 + N 步」一行摘要（点击展开/收起，与 ZCode 行为一致），历史消息同样默认折叠；AI 回答新增悬停显示的「复制」按钮（触屏常显）。

## 本次改动

按 GPT 给出的改进清单（参考 EvoFlow Plan 模式设计）完成三个 P0，设计文档 `docs/design/2026-10-28-dependency-plan-evidence-verification.md`：

- **P0-1 依赖计划**：`PlanStep` 增加 `id/dependsOn/acceptance`；`PlanJsonParser.normalize` 按位置重编 id（s1..sn）、映射依赖引用、断环退化为并行；Planner 提示词要求输出 id/depends_on/acceptance 并说明"后续步骤用到上游产出时必须标 depends_on"；`WorkersNode.dependencyWaves` 按 Kahn 分层波次执行（波内并行、波间串行），上游结论压缩后注入下游任务提示，上游失败/跳过时下游标记跳过不执行。
- **P0-2 选择性返工**：新增 `GraphState.REVISE_STEPS`；Verifier 的 verdict 可带 `steps`（0 起序号）点名返工；Workers 返工轮对未点名且结论可复用的步骤直接复用（发"复用上轮结果"事件）；checkpoint 增加 `reviseSteps` 字段并随 resume 恢复（未加 DB 列，进程重启后降级为全量重跑，用 11 参兼容构造保旧调用点）。
- **P0-3 证据验收**：`VerifierNode.evidenceGaps` 结构化门禁——结果为空/失败/跳过/取消、承诺产出文件（author 角色或 detail/acceptance 匹配"生成|输出|导出|保存+文件|PDF|表格|文档"）但结果无文件名 → 不进模型直接 revise 并点名；模型评价改为逐条"计划+完成判据+结果摘要"核对；Workers/Verifier/Aggregator 在返工轮统一"与计划对齐取最后一轮"视图（TASK_RESULTS 追加语义下取末尾 plan.size() 条），避免旧轮结论串味。
- 前端：RunInspector「计划」页签展示依赖（`依赖 01、02`）与验收判据；agent-trace 时间线自动呈现波次/复用/跳过事件。
- `AgentRunView.PlanStepView` 增加 `id/dependsOn/acceptance`。

## 验证结果

- `mvn test`：327 通过、0 失败、1 跳过（新增 PlanJsonParser 依赖用例 4、WorkersNodeDependencyTest 6、VerifierNodeEvidenceTest 7）。
- `mvn -DskipTests package` 成功；后端已 `launchctl kickstart -k` 重启，`GET /api/health` ok；`npm run build` 通过。
- 建议真实任务验证："调研三个竞品并生成对比报告"应看到 s1 调研（并行）→ s2 报告依赖 s1、提示词含上游结论；author 步骤结论不含文件名时应触发"证据核对未通过"返工。

## 遗留事项

- 计划草稿/修订/激活的 Plan 模式交互（P1）、步骤级 checkpoint、工作区文件系统级证据核对未做。
- `GraphCheckpoint.reviseSteps` 未持久化到 DB 列：进程重启后的返工恢复退化为全量重跑（与旧行为一致）；如需完整支持要加 Flyway 列。
- P1 清单（跨会话任务中心、Goal 模式、Agent 能力组合、任务经验、预算控制、端到端评测集）见设计文档 §5。

## 环境注意事项

- 后端已用新 JAR 重启；前端 dev server 运行中。
- 仓库有两个未跟踪的 Finder 副本垃圾文件（`docs/design/2026-10-25-jev-intent-routing 2.md`、`evaluation/eval-sets/run_intent_gate 2.py`，均为改动前旧版快照），可手动删除；`release/` 构建产物已加入 .gitignore。
