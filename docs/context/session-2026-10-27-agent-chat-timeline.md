# 会话交接：会话区改版为智能体时间线（2026-10-27/28）

## 追加改动（10-28）：主画布改为对话即界面

用户反馈工作台仍是「仪表盘分区」结构，且时间线要点「对话」才可见。已重构：

- `frontend/src/views/SuperAgent.vue`：主画布改为全高会话（ChatRoom）+ 输入框的对话式结构；删除 RunPhases 阶段条、PlanBoard 执行计划区、RunTimeline 执行过程区、DeliverableView 交付结果区、会话抽屉/「会话记录」开关和 legacy-chat 兜底（统一走会话视图）。时间线（agent-trace）随消息直接显示在主画布，无需展开。
- **二轮反馈**：顶部「当前任务 + 目标大标题」与对话里的提问气泡重复，已整块移除（RunHeader 不再使用并删除文件，`activeGoal` computed 一并移除）。刷新/导出收成画布右上角悬浮小图标（`.canvas-actions`，出现工具确认横幅时自动下移）；继续/停止入口保留在 RunInspector「运行」页签与输入框停止键，中断回答的「继续生成」保留在消息内。
- `frontend/src/components/workspace/RunInspector.vue`：新增「计划」页签（承接 PlanBoard：子任务标题/角色/状态/产出，状态推断逻辑照搬），移除「在对话中查看完整引用」入口；页签顺序：计划/来源/文件/用量/工具/运行。
- 删除不再被引用的组件文件：`RunHeader.vue`、`RunPhases.vue`、`PlanBoard.vue`、`RunTimeline.vue`、`DeliverableView.vue`。
- 交付区信息去向：回答正文与文件 → 会话消息本身；验收状态/反馈 → RunInspector「运行」页签（原有）；引用切片 → 消息内引用卡 + 「来源」页签；恢复入口 → 「运行」页签。

## 本次改动

参考 ZCode 式执行视图，把超级智能体「会话记录」里 AI 消息的渲染从「iMessage 气泡 + 底部执行过程胶囊」重构为智能体时间线结构：

- `frontend/src/components/ChatRoom.vue`：
  - AI 消息新增 `agent-trace` 区块，置于正文之前：顶部状态行（执行中显示 `工作中 X 秒`，每秒跳动；结束后显示 `已完成/已中断/执行失败 · 用时 X 秒`），下面是步骤列表（图标 + 阶段词/角色名 + 摘要 + `持续了 X 秒`），再接流式正文。
  - 步骤图标按 phase 映射：plan→ListChecks（规划）、work→Search（执行）、verify→ShieldCheck（校验），兜底 CircleDot；`agent` 字段优先于 phase 词展示。
  - 每步「持续了 X 秒」由前端实测：相邻 activity 的 `at` 时间戳之差（首步相对 `startedAt`），不足 1 秒不显示。仅总时长与步骤相对时长，无后端逐步耗时。
  - 点击步骤可展开原始 `detail`（仅当 detail 与 summary 不同，如旧格式 "Step N: ..." 原文）。
  - 删除旧的 `activity-trace` 胶囊渲染、`activitySummary`/`openActivity`/`view-execution` emit、`collapsibleActivities` 字段；秒级计时器仅在 `connectionStatus === 'connecting'` 时运行，卸载时清理。
- `frontend/src/views/SuperAgent.vue`：
  - `parseActivity` 返回 `{phase, agent, summary, detail}`（不再拼 label）；activity SSE 处理入栈时带 `at: Date.now()`，同步进 `activeRun.events`。
  - 占位回答消息带 `startedAt`；`continueMessage` 续跑时重置 `startedAt/finishedAt`。
  - 新增 `sealActiveAnswer`/`captureLiveTrace`/`restoreLiveTrace`：finishStream、recoverFinishedRun、stopGeneration、429/连接失败分支结束前封存实时轨迹，历史重载后回填到最后一条助手消息，避免刷新丢失时间线。
  - `loadRunsForConversation` 水合：历史消息若无 activities 且 run 有 events，用语义事件补时间线；用 run 的 `createdAt/updatedAt` 补起止时间（历史步骤无逐步时长）。
  - 失败路径（429、最终连接失败）给 answer 补 `status = 'FAILED'`，状态行显示「执行失败」。

## 验证结果

- `npm run build` 成功（12s，仅既有 chunk 体积告警，两轮均通过）。
- 后端 `GET /api/health` 返回 ok；前端 dev server 此前未运行，已按 AGENTS.md 重新 `launchctl submit com.zwx.yu-ai-agent.frontend`，`http://127.0.0.1:3000/` 返回 200，HMR 已加载全部改动。
- 未改后端代码，无需重新打包。

## 遗留事项

- 「持续了 X 秒」是前端相邻事件的间隔估计，不是后端逐步真实耗时；如需精确可后续在 ActivityEvent 中加 durationMs 并入库。
- 历史消息时间线来自 run.events 水合，没有逐步时长与时间戳；同一会话里更早轮次（非当前 run）只显示正文，不带时间线。
- 原执行计划卡片里的「用户请求」完整提示词在新「计划」页签中不再展示（只留标题/角色/状态/产出）；如需查看可看 RunInspector「工具」页签的调用审计。
- 未提交 Git；工作区同时存在上一轮意图两分类的未提交改动。

## 环境注意事项

- 前端 launchctl 作业 `com.zwx.yu-ai-agent.frontend` 本次为重新 submit（此前不存在/未运行），日志 `/tmp/yu-ai-agent-frontend.log`。
- 后端 launchctl 作业 `com.zwx.yu-ai-agent.backend` 运行中，未重启。
