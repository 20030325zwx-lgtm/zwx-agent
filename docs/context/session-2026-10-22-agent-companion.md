# 会话摘要：超级智能体页内数字人（2026-10-22）

## 改动内容

- 新增 `frontend/src/components/AgentCompanion.vue`：纯 SVG/CSS 的页内数字人“星枢”，无新增依赖、无外部素材。
- 支持状态：`idle` / `listening` / `thinking` / `working` / `speaking` / `done` / `error`，对应待机漂浮、聆听、思考轨迹、执行环、说话口型与音量条、完成笑脸与星光、错误表情。
- 气泡展示状态与当前活动说明；悬停或点击头像可固定/取消，`aria-live` 播报状态，支持 `prefers-reduced-motion` 和窄屏缩放。
- `frontend/src/views/SuperAgent.vue` 接入状态机：
  - 发送/上传/规划/检索/工具调用/流式回答/完成/停止/中断/错误分别驱动状态；
  - 进入空会话时短暂问候“你好，我是星枢，有什么可以帮你？”；
  - 完成后约 2.6 秒自动回到待机。

### 统一形象入口

- 新增 `frontend/src/components/AsterAvatar.vue`：可复用的迷你数字人头像，带轻微漂浮、眨眼和星光动画，尺寸跟随父容器。
- 首页超级智能体入口卡片图标由 `✦` 换成迷你数字人（`Home.vue`）。
- 超级智能体顶栏标识、会话空状态图标、AI 消息头像统一使用同一形象（`SuperAgent.vue`、`ChatRoom.vue`、`AiAvatarFallback.vue`）；`love` 等其他类型仍保留原 emoji 兜底。

## 验证结果

- `frontend` 下 `npm run build` 通过。
- 使用 Playwright + 本地 mock API 实测状态切换：`state-working → state-speaking → state-done → state-idle` 均按预期触发，已截图确认布局与动画。
- 首页卡片、超级智能体顶栏、空状态和会话头像均已截图确认使用迷你数字人。
- 未触碰后端、数据库和 `application-local.yml`。

## 遗留事项

- 当前形象为内置 SVG 占位设计，后续可替换为 Lottie/序列帧或 Live2D；替换时保持 `state` 契约即可。
- 数字人位置固定在右下角（输入框上方），未做拖拽和位置记忆。
- 本轮改动尚未提交。

## 环境注意事项

- 视觉验证使用临时 mock API（8099）和独立 Vite 端口，结束后已关闭；未改动项目运行配置。
