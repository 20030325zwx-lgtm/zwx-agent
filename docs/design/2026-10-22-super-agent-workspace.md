# 超级智能体工作台改造方案（2026-10-22）

> 状态：批次 0 + 1 已实施（2026-10-22）；批次 2–4 待做。目标是把 `/super-agent` 从“聊天记录页”升级为“任务工作台”，让计划、执行过程、交付物和对话各自有明确位置。

## 1. 问题判断

当前页面并不是没有能力，而是**信息层级错位**：

- 主舞台是消息气泡，真正的任务计划只存在于折叠的 `activity` 里。
- `agent_run.checkpoint` 已经持久化了 `plan`、`taskResults`、`activities`、`events`、`verificationStatus`，但 `GET /runs/{runId}` 只返回数量，前端拿不到结构化过程。
- 工作区文件接口 `GET /workspace/files` 已存在，但前端没有使用，产出物只能靠消息里的附件卡片发现。
- 刷新或恢复历史会话后，前端把 `activities` 重置为空，执行过程不可回看。
- 校验结果（通过/返工/未验收）没有暴露，用户无法判断交付质量。

因此改造重点不是再画一个聊天框，而是**把已有运行数据变成可读、可操作的任务现场**。

## 2. 目标与边界

### 目标

1. **任务优先**：打开会话先看到“这次任务做到哪、计划是什么、产出了什么”，对话降为辅助。
2. **过程可见**：规划 → 执行 → 验收 → 交付四阶段实时呈现，语义化事件分组，不展示原始日志。
3. **交付物前置**：最终回答、引用、生成文件、工作区文件都在结果区可直接操作。
4. **可恢复**：刷新、断线、中断后，计划与时间线能从 run checkpoint 重建。
5. **简单问题不臃肿**：单步骤、无产物的问答自动使用精简视图。

### 非目标

- 不做可视化工作流编辑器或手动拖拽编排。
- 不在本阶段修改租户/用户隔离模型。
- 不要求新增数据库表；先复用 `agent_run.checkpoint`。
- 不删除现有聊天能力，只改变它在页面中的位置与默认展开状态。

## 3. 目标形态

```text
┌──────────────┬──────────────────────────────────────────────┬─────────────────┐
│ 会话 / 任务   │  任务标题   [进行中]      停止  继续  导出     │  上下文检查器    │
│              ├──────────────────────────────────────────────┤                 │
│ ● 进行中      │  目标：为先员工手册整理迟到与请假规则          │  来源            │
│ ○ 已完成      │                                              │  引用切片        │
│              │  [规划 ✓] → [执行 ●] → [验收 ○] → [交付 ○]   │  工作区文件      │
│              │                                              │  运行信息        │
│              │  计划板                                       │  验收状态        │
│              │  ┌ 调研员 · 检索手册 ┐  ┌ 撰写员 · 汇总规则 ┐ │                 │
│              │  │ 执行中…            │  │ 等待中            │ │                 │
│              │  └───────────────────┘  └───────────────────┘ │                 │
│              │                                              │                 │
│              │  执行时间线（按 plan / work / verify 分组）    │                 │
│              │  交付结果（Markdown + 引用 + 文件卡片）        │                 │
│              ├──────────────────────────────────────────────┤                 │
│              │  对话记录（可折叠，默认收起）                  │                 │
│              │  [ 输入任务 / 附件 / 知识库 / 联网 ]           │                 │
└──────────────┴──────────────────────────────────────────────┴─────────────────┘
```

布局规则：

- 左栏保留会话历史，但增加“进行中 / 已完成”分组。
- 中栏是主画布，从上到下为：任务头 → 阶段条 → 计划板 → 时间线 → 交付结果 → 对话记录。
- 右栏是可切换检查器：`来源`（RAG/网页引用）、`文件`（工作区产出）、`运行`（run 元信息、验收、恢复）。
- 底部输入框改为常驻“任务指令条”；发送后输入框收起为一行，任务头接管状态展示。

## 4. 交互与状态

### 4.1 任务生命周期

| run 状态 | 页面表现 |
| --- | --- |
| `RUNNING` | 阶段条高亮，计划卡片显示排队/执行中，时间线实时追加，输入框可继续补充指令或停止 |
| `COMPLETED` | 交付结果默认展开，时间线自动折叠，计划卡片显示完成 |
| `INTERRUPTED` / `FAILED` | 顶部出现恢复卡片：`从最近检查点继续`、`查看已完成部分` |
| `unverified` | 交付结果旁显示“未验收”徽标，不在视觉上伪装成已通过 |

### 4.2 简单问题降级

当 `plan.size === 1` 且没有生成文件、没有联网/知识库引用时：

- 隐藏计划板，阶段条压缩为一行；
- 直接展示回答；
- 对话记录仍可展开。

避免“问一句天气”也进入重型工作台。

### 4.3 对话的位置

“对话”不是被删除，而是变成**任务的审计日志/补充通道**：

- 默认收起为一行“本轮对话 · N 条”；
- 展开后复用现有 `ChatRoom` 消息渲染、引用预览和文件卡片；
- 新消息进入后，任务画布顶部出现“新一轮”分隔，不覆盖上一轮结果。

## 5. 前端改造

### 5.1 页面拆分

| 文件 | 职责 |
| --- | --- |
| `views/SuperAgent.vue` | 只负责数据编排、SSE 生命周期和布局壳 |
| `components/workspace/RunWorkspace.vue` | 主画布布局与响应式 |
| `components/workspace/RunHeader.vue` | 任务标题、目标、状态、停止/继续/导出 |
| `components/workspace/RunPhases.vue` | 规划→执行→验收→交付阶段条 |
| `components/workspace/PlanBoard.vue` | 计划卡片、角色、执行状态 |
| `components/workspace/RunTimeline.vue` | 语义事件分组与增量渲染 |
| `components/workspace/DeliverableView.vue` | 最终回答、引用、产物卡片 |
| `components/workspace/RunInspector.vue` | 右栏容器与页签 |
| `components/workspace/SourcesPanel.vue` | RAG 引用与网页来源 |
| `components/workspace/FilesPanel.vue` | 工作区文件清单、预览、下载 |
| `components/workspace/RunMetaPanel.vue` | run 状态、验收、恢复次数、重试 |
| `components/workspace/TaskComposer.vue` | 任务输入、附件、知识库/联网开关 |
| `composables/useRunWorkspace.js` | 把 SSE 事件和历史 run 归一化为同一份 run 视图模型 |

`ChatRoom.vue` 不重写，只增加 `compact` / `embedded` 模式，供对话抽屉复用。

### 5.2 统一数据模型

前端以 run 为单一事实来源，不再把 `activities` 只挂在消息对象上：

```js
runView = {
  id, status, goal,
  plan: [{ index, title, role, roleLabel, detail, status }],
  phases: { plan, work, verify, deliver },
  events: [{ phase, agent, summary }],
  taskResults: [{ index, title, summary }],
  verification: { status, feedback },
  answer, citations, files,
  canResume, canCancel
}
```

实时 SSE 与历史 `GET /runs/{runId}` 都映射到该结构，页面组件不关心数据来自流还是检查点。

### 5.3 刷新与恢复

- 会话消息加载后，对每条带 `executionRunId` 的助手消息拉取 run 详情。
- 后端提供批量接口，避免 N+1 请求。
- SSE 重连/恢复继续沿用现有 `reconnect` / `resume` 链路，前端按 `lastEventId` 去重。

## 6. 后端改造

### 6.1 扩展 run 详情接口

`GET /api/ai/manus/conversations/{conversationId}/runs/{runId}` 增加结构化字段（保持旧字段兼容）：

```json
{
  "id": "...",
  "status": "RUNNING",
  "lastNode": "workers",
  "nextNode": "verifier",
  "plan": [{ "index": 0, "title": "...", "role": "researcher", "roleLabel": "调研员", "detail": "..." }],
  "taskResults": [{ "index": 0, "title": "...", "summary": "..." }],
  "events": [{ "phase": "work", "agent": "调研员", "summary": "开始执行（1/2）：检索手册" }],
  "verification": { "status": "pass", "feedback": "" },
  "canResume": false,
  "canCancel": true,
  "createdAt": "...",
  "updatedAt": "..."
}
```

- 数据直接来自 `GraphCheckpoint`，无需迁移。
- `taskResults` 当前是 `List<String>`，按索引与 `plan` 对齐即可；不强制改图模型。
- 新增 `GET .../runs` 返回会话下的 run 摘要列表，用于批量回填和后续任务列表。

### 6.2 实时计划事件

当前 SSE 只有 `run`、`activity`、`rag-trace`、`interrupted`、消息和 `[DONE]`。建议在 planner 产出计划后补一个 `plan` 事件：

```text
event: plan
data: [{"index":0,"title":"...","role":"researcher","roleLabel":"调研员","detail":"..."}]
```

前端收到后立即渲染计划板；断线恢复时以 run 详情为准，不依赖事件回放完整性。

### 6.3 文件与引用

- 前端接入已有 `GET /api/ai/workspace/files?agentKey=super&conversationId=...`。
- 预览复用 `GET /api/ai/manus/files`；PDF/图片直接打开，文本类先做下载与简单预览。
- 引用来源继续使用现有 `rag-trace` 与消息 `ragRetrievalTrace`，在右栏聚合展示。

## 7. 分批实施

### 批次 0：数据可用（1–2 天）

- 扩展 run 详情接口与 run 列表接口。
- 新增 `plan` SSE 事件。
- 前端新增 `getManusRun` / `listManusRuns` / `listWorkspaceFiles` API。
- 历史会话加载时回填 run 视图模型。
- 验收：刷新页面后计划、事件、验收状态仍可见；现有测试通过。

### 批次 1：任务工作台首屏（3–4 天）

- 新布局壳：左会话、中画布、右检查器。
- 实现任务头、阶段条、计划板、时间线、交付结果。
- 对话区折叠为审计日志，输入框常驻。
- 简单问题自动降级。
- 验收：提交任务后计划卡片与时间线实时出现；完成/中断/失败状态正确；窄屏可用。

### 批次 2：交付物与上下文（3–4 天）

- 右栏接入文件清单、引用来源、run 元信息。
- 产物卡片支持预览、下载、复制路径。
- 支持导出本轮结果为 Markdown。
- 验收：生成 PDF/文件后能在文件页签看到并打开；知识库引用与网页来源可回看。

### 批次 3：任务连续性与入口（4–5 天）

- 会话侧栏按进行中/已完成分组，首页增加“最近任务”。
- 支持从画布恢复、取消、重试。
- 可选：独立 run 路由 `/super-agent/runs/:runId` 支持深链。
- 验收：中断后可从最近节点继续；刷新、切换会话、跨设备打开同一会话状态一致。

### 批次 4：打磨与质量（2–3 天）

- 空状态改为任务模板（拆解目标、调研写报告、分析数据、知识库问答）。
- 事件列表虚拟化/分页，避免长时间运行卡顿。
- 键盘操作、`prefers-reduced-motion`、窄屏适配。
- 补后端单测与一条前端端到端流程。

## 8. 风险与对策

| 风险 | 对策 |
| --- | --- |
| SSE 契约变更影响旧前端 | `plan` 事件为新增事件，旧客户端忽略即可；run 接口只加字段 |
| 计划与消息状态重复 | 以 run 为单一事实来源；消息只保留最终回答与文件引用 |
| 长时间运行事件过多 | 前端按阶段分组、折叠旧事件；后端限制单次返回事件数并支持分页 |
| 轮次切换导致状态串台 | 每个 run 独立视图模型，切会话先清空再回填 |
| 简单问答被工作台拖慢 | 单步骤/无产物自动降级为回答优先 |
| 右栏信息过载 | 默认只显示“来源”，文件与运行信息按需切换；移动端改为底部抽屉 |

## 9. 推荐的第一步

先做**批次 0 + 批次 1 的一个纵向切片**：

1. 扩展 run 详情与 `plan` 事件；
2. 把 `SuperAgent.vue` 改成“任务画布 + 可折叠对话”；
3. 只实现计划板、时间线、交付回答三块，右栏先放引用与文件两个页签的最小版。

这样一轮就能把页面从“聊天框”变成真正的任务工作台，同时保留现有聊天和恢复能力，后续再补任务入口、导出和打磨。


## 10. 已实施范围（批次 0 + 1）

- 后端新增 `AgentRunView` / `AgentRunSummary`，`GET /manus/conversations/{id}/runs/{runId}` 返回计划、任务结果、语义事件、验收状态和恢复能力；新增 `GET /manus/conversations/{id}/runs` 列表。
- SSE 新增 `plan` 事件，planner 产出后立即推送结构化计划；恢复执行时从 checkpoint 补发。
- 前端新增 `TaskComposer`、`RunHeader`、`RunPhases`、`PlanBoard`、`RunTimeline`、`DeliverableView`、`RunInspector`。
- `/super-agent` 改为“任务画布 + 右栏检查器 + 底部指令条”；右栏占满整列并可收起（44px 竖向入口，状态持久化），输入框只在中栏下方。
- 会话记录折叠为审计日志，`ChatRoom` 新增只读模式。
- 历史会话加载后按 `assistantMessageId` 回填最近 run，计划、时间线、验收状态可跨刷新恢复。
- 单步骤、无产物、无引用的任务自动进入精简视图。
- 验证：后端 201 个测试通过（1 跳过）；前端 `npm run build` 通过；Playwright + mock API 验证计划、时间线、交付、会话抽屉和刷新回填。

### 尚未覆盖

- 任务侧栏分组、首页最近任务、独立 run 深链、导出 Markdown（批次 3）。
- 工作区文件预览只做下载链接，未内嵌 PDF/文本预览。
- 页面加载时若存在其他实例上的 RUNNING run，不会自动重连实时流，只展示已持久化状态。
