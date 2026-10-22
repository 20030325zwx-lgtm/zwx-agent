# 会话摘要：超级智能体任务工作台（批次 0 + 1）（2026-10-22）

## 改动内容

### 后端

- 新增 `AgentRunView`：run 详情返回 `plan`、`taskResults`、`events`、`verification`、`canResume`、`canCancel`、用户/助手消息 ID 和时间戳，保留原数量字段。
- 新增 `AgentRunSummary` 与 `GET /api/ai/manus/conversations/{id}/runs`，按创建时间倒序返回轻量 run 列表。
- `GET /api/ai/manus/conversations/{id}/runs/{runId}` 改为返回 `AgentRunView`。
- SSE 新增 `plan` 事件：planner 产出后推送结构化计划；恢复执行时从 checkpoint 补发。
- `AgentRunService` 增加 `findScopedRuns`。
- 新增 `AgentRunViewTest`。

### 前端

- 新增 `components/workspace/`：
  - `TaskComposer`：任务指令条，支持附件、知识库开关、停止；
  - `RunHeader`：目标、状态、阶段、恢复/停止/对话入口；
  - `RunPhases`、`PlanBoard`、`RunTimeline`、`DeliverableView`、`RunInspector`。
- `SuperAgent.vue` 改为任务画布布局：中栏任务现场、右栏来源/文件/运行检查器、底部指令条。
- 右栏占满工作区整列高度，不再被输入框打断；支持通过检查器右上角按钮收起，收起后显示 44px 竖向“工作区”入口，状态写入 localStorage。
- 会话记录折叠为审计日志；`ChatRoom` 新增 `hideInput` 只读模式。
- 历史会话加载后按 `assistantMessageId` 回填最近 run，计划和事件可刷新恢复。
- 单步骤、无产物、无引用任务自动使用精简视图。
- 新增前端 API：`listManusRuns`、`getManusRun`、`listWorkspaceFiles`。

### 文档

- 新增 `docs/design/2026-10-22-super-agent-workspace.md`（批次 0 + 1 已实施，批次 2–4 待做）。
- 更新 `docs/design/README.md` 与 `docs/ROADMAP.md`。

### 卡住问题修复（20:12 运行）

- 现象：run `7381b033-d937-4b88-b7df-9b34f1d77882` 在 20:13:40 已 COMPLETED，但页面仍停留在“正在思考”，只显示规划器事件。
- 原因方向：worker 的工具事件原先只在 workers 节点整体完成后才进入 state，执行期间前端长时间无新事件；终态事件也可能因 SSE 连接异常未被前端消费。
- 修复：
  - `WorkersNode` 通过 `RunContext.activitySink` 实时推送子任务开始、工具调用、完成/失败事件；orchestrator 对实时事件与检查点事件做去重。
  - `RunStream.send` 改为同步，保证多 worker 线程与执行线程推送安全。
  - 增加 5 分钟 run 硬超时，到期自动停止并给出系统事件。
  - 前端对 RUNNING run 每 5 秒轮询 `GET runs/{runId}`；发现进入终态后自动重新拉取消息、答案和文件，修复 SSE 丢失导致的假死。
  - 任务头增加“已运行/用时”计时和“刷新”按钮。
- 验证：后端 201 个测试通过（1 跳过）；前端构建通过。

## 验证结果

- 后端 `mvn test`：201 个测试，0 失败，0 错误，1 跳过。
- `mvn -DskipTests package` 成功；新 JAR 已重启，`GET /api/health` 返回 200。
- 前端 `npm run build` 成功。
- 实测右栏高度 818px（与工作区同高），输入框仅位于中栏下方；收起/展开与 localStorage 持久化正常。
- 使用 Playwright + mock API 验证：空状态、计划板、时间线、交付区、会话抽屉、刷新回填均正常。
- 后端启动日志：`/tmp/yu-ai-agent-backend.log`；旧 JAR 备份：`/tmp/aster-agent-0.0.1-SNAPSHOT.backup.jar`。

## 遗留事项

- 批次 2：工作区文件内嵌预览、产物卡片增强、结果导出 Markdown。
- 批次 3：任务侧栏分组、首页最近任务、独立 run 深链、运行中任务自动重连。
- 页面加载时若存在其他实例上的 RUNNING run，当前只展示已持久化状态，不自动接入实时流。
- 尚未用真实登录 token 在浏览器中跑一遍真实任务链路，建议下次启动后手动验证。
- 本轮改动尚未提交。
