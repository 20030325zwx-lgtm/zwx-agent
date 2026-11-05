# 会话摘要：视频创作整改 P0（2026-11-06）

## 本次改动

按《视频创作整改需求说明书》实施 P0（用户确认范围：只做 P0；导航收敛为「制作 + 画布」）：

1. **V44 迁移**：`video_storyboard_draft`（rows JSONB + raw_text，状态 READY/CONSUMED/DISCARDED/PARSE_FAILED）。
2. **结构化拆镜**：`PromptOptimizationService.storyboardDraft()` 新模式 + `StoryboardDraftService`
   （ensureShortfilm / generate / get / adopt / discard）；采纳用条件状态转移保证一次有效写入；
   替换模式只删未生成且无候选的镜头；来源版本不一致 409（force 显式放行）。
3. **镜头排序/插入**：`moveShot`（三步换号避开唯一约束）+ `createShot(..., afterShotId)` 两段式平移；
   顺带修复 `moveEpisode` 直接互换撞 `uq_video_episode_number` 的潜在 bug（bug 记录见
   `docs/bugs/bug-2026-11-06-episode-move-unique-conflict.txt`）。
4. **项目设定**：`videoStyle/videoAspect/videoTargetSeconds` 键 + 服务端校验 +
   `GET/PUT /ai/creative/projects/{id}/settings`。
5. **前端制作视图**：路由 `production`；`ProjectProductionTab`（五阶段栏 + 摘要 + 缺什么提示）；
   `StyleStage`（复用 ImageStyleCenter）/`StoryStage`（自动保存 + 整理故事 + 拆镜入口）/`
   DraftReviewDialog`（行编辑 + 采纳）/`ShotBoardStage`（内嵌分镜看板）；成片阶段空态。
   导航收敛为 制作/画布 + 工具区（设定/素材/任务），旧 tab 路由保留供深链接。
6. **ShotInspector**：字段 800ms 自动保存；头部 ↑/↓/＋插入。

## 验证

- 后端新增测试全过；creative 模块全量测试通过；两个既有失败（ToolGuardHookTest/MediaJobSchedulerTest）与本次无关。
- `npm run build` 通过；V44 已应用（本地重启即迁移，迁移前已 pg_dump：`~/Desktop/agent/backups/zwx-agent-pre-V44-*.dump`）。
- 端到端登录态流程见设计文档 `docs/design/2026-11-06-video-production-p0.md` 验证节。

## 实测反馈修正（同日）

- 画风保存后自动进入下一阶段；主行动按钮在已保存态变为「下一步：写故事」。
- 画布节点画风默认继承项目画风（`getProjectSettings` → `projectStyle`），跨视图统一；
  分镜/视频阶段提供「在画布中编排」链接并说明镜头暂不自动同步（P3 才做双视图）。
- 制作页背景改 `--wb-bg`，与画布一致。

## 修正补记

- `ctx.route` 误用 `.value`：Shell provide 的 route 是 `useRoute()` 的响应式对象而非 ref，
  导致「下一步：写故事」点击无反应、`?stage=` 深链接失效；已改为直接读 `ctx.route.query`。

## 遗留（P1/P2）

- 生成预检/统一任务摘要/「较早设定」贯通/镜头级风格覆盖（P1）。
- 成片拼接导出（P2，需 ffmpeg 运行环境）；当前成片阶段为空态。
- 画布镜头映射与双视图同步（P3）。

## 环境注意

- 本地后端连的是隧道后的库（15432），重启即应用迁移——迁移前先备份。
- 草稿生成是同步 LLM 调用（十几秒量级），不要改成「免费/秒出」类文案。
- `ImageStyleCenter` 在项目画风场景传 `has-reference=false`，会自动隐藏「跟随原片」。

## 运维补记：对话模型切换（2026-11-06）

- DashScope key 过期，用户 chat/vision 绑定原指向 qwen-plus / qwen3-vl-plus（已失效）。
- 现有 KKAI 两个 key 是 image / Seedance 分组，实测开不了 gpt-5.6-terra（503 model_not_found）。
- 用户提供对话分组新 key：新增供应商「KKAI 对话中转站」（provider 23，openai-compatible，
  https://api.kkrich.ltd/v1，secret 按	ConfigSecretCipher AES-GCM 入库），模型 gpt-5.6-terra（tools+vision，启用）、
  gpt-5.6-luna（停用备用）。
- 重绑 USER 绑定：chat → gpt-5.6-terra（row 49），vision → gpt-5.6-terra。文本与视觉均已用该 key 实测 200。
- agent/novel/optimize 无独立绑定，运行时回退 chat → 一并跟随 terra。
- 仍在 DashScope 的：embedding（知识库向量）与 UI Mock 供应商的 wanx 绑定（未清理）；
  DashScope 供应商行保留未删（key 过期，若续费即恢复）。
