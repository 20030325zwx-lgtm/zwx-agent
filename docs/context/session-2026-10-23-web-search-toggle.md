# 会话摘要：工作台联网搜索开关（2026-10-23）

## 改动内容

- `TaskComposer` 启用已有的联网搜索按钮（Globe 图标），位置和样式与“检索知识库”按钮一致；默认关闭，点击后本轮允许联网；按钮带自定义悬浮说明。
- `SuperAgent` 将 `webSearch` 随消息提交，并传给 `/ai/manus/chat`；侧边数字人提示会显示“正在联网搜索与规划任务”。
- 后端 `/manus/chat` 与 `/runs/{runId}/resume` 增加 `webSearch` 参数，默认 `true` 兼容旧客户端。
- `ToolFactory.createTools(..., webSearchEnabled)`：关闭时移除 `searchWeb`、`scrapeWebPage`、`downloadResource` 三个内置联网工具，保留文件、PDF、时间、MCP 等工具。
- `PlannerNode` 在关闭联网时明确提示 researcher 不可用；`WorkersNode` 同样在 worker 提示词中禁止搜索/抓取/下载。
- 修复既有工具名不一致：实际工具名是 `searchWeb`，但 `WorkerRole` 和 `ToolGuardHook` 使用的是 `webSearch`，导致 researcher 无法使用搜索工具；已统一为 `searchWeb`，并保留 `webSearch` 兼容别名。
- 新增 `ToolFactoryWebSearchTest` 覆盖联网工具开关。
- 工具栏按钮增加自定义悬浮提示：上传图片（可多选）、联网搜索已开启/已关闭、知识库检索已开启/已关闭。
- 多文件上传打通：前端逐个上传图片并收集 objectKey，`/manus/chat` 用重复 `imageObjectKey` 参数提交；后端改为 `List<String>`，逐张校验、逐张视觉分析，并全部写入用户消息。

## 验证结果

- 后端：212 个测试通过（1 跳过）。
- 前端：`npm run build` 通过。
- Playwright：工作台输入区出现联网按钮，默认“本轮不联网搜索”，点击后变为“本轮允许联网搜索”并高亮。
- 请求验证：开启后实际请求包含 `webSearch=true`。
- 多图验证：一次选择 2 张图片，产生 2 次上传请求；聊天请求包含 `imageObjectKey=img-1` 和 `imageObjectKey=img-2`。
- 悬浮验证：联网按钮 tooltip 显示“联网搜索：已关闭，点击开启”。
- 后端已重新打包并重启，`/api/health` 返回 200。

## 遗留事项

- resume 的 `webSearch` 由前端当前状态传入；页面刷新后历史 run 没有保存该开关，恢复时默认允许联网。
- MCP 工具不受该开关影响；如果 MCP 自身提供联网能力，仍会保留。
- 本轮改动尚未提交。

## 环境注意事项

- 后端仍由 `com.zwx.yu-ai-agent.backend` launchctl 任务托管，日志 `/tmp/yu-ai-agent-backend.log`。
- 未读取、修改或提交 `src/main/resources/application-local.yml`。
