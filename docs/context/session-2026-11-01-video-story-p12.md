# 视频剧情域（P0+P1+P2）会话（2026-11-01 晚）

## 改动内容

按 `docs/design/2026-11-01-video-production-management-research.md` 落地主优先级三阶段（实施记录：`docs/design/2026-11-01-video-story-p12-batch.md`）：

- **V17 迁移**：`video_episode`/`video_scene`/`video_shot`（version 乐观锁 + 生产状态机）/`video_character`/`video_location`/`video_continuity_note`（事实/状态/伏笔）/`video_shot_candidate`（部分唯一索引保证每镜头单选）/`video_asset_ref`。全部 V13 组合外键模式。
- **后端 `creative/story/`**：`StoryContentService`（CRUD/确认/交换排序/乐观锁 409）、`ShotProductionService`（候选预建+prompt 冻结+幂等键受理+受理失败回滚、上传候选、选片/换选/取消）、`ShotCandidateResumeScheduler`（R02 模式回写产物/失败）、`StoryController` 全套 REST。
- **P0 优化器重构**：`PromptOptimizationService` 加 mode（IMAGE 旧路径零迁移兼容 / VIDEO_PROMPT / VIDEO_STORY / VIDEO_STORYBOARD），服务端按 ID 定向读取上下文（设定+角色/地点+连续性+镜头字段），三套系统提示词按 Seedance 指南编写；视频模式结构化 JSON（optimizedText/changes/suggestions/missing）解析失败如实 422；仅 VIDEO_PROMPT 受 500 字上限（剧情/分镜稿是创作文档，只受 8000 上限——实施中发现的边界错误已修）。
- **前端**：新组件 `story/StoryWorkspace.vue`（剧集/场次/分镜表 + 优化剧情/细化分镜入口 + 建议稿采纳写回/复制）、`story/ShotDrawer.vue`（字段分区 + 优化对比采纳 + 生成按钮（默认 4 秒+计费提示）+ 候选播放/选定/换选/上传）、`story/StorySettingsPanel.vue`（角色/地点/连续性）；VIDEO 项目默认落「剧情」tab；快速生成优化按钮按项目 kind 自动分流（P0 验收点）。

## 验证结果

- 单测 6/6（优化模式解析 5 + ShotStatus 1）；定向回归 23 类 **131/131**；V17 迁移已应用；前后端构建通过。
- 浏览器 E2E（无计费）：建剧集→场次→镜头→字段保存→**优化提示词**（真实 LLM，结构化建议符合 Seedance 指南：动作部位/顺序/单运镜/衔接，明确剔除心理描述）→采纳→上传候选（flower.mp4）→待选片→选定→播放；**优化剧情/细化分镜入口**真实 LLM 验证（分镜稿 6 镜带动作/景别/运镜/衔接/时长）。
- **唯一一次真实生成（用户授权，4s/720p）**：镜 2 生成 → 约 4.5 分钟 → 调度器自动回写候选（GENERATING→CANDIDATE，镜头→待选片）→ UI 播放（画面与优化提示词一致）→ 人工选定 → 已选定。计费 1 次成功，无重试。
- 过程修复 3 个问题：① 上传候选推进镜头版本导致抽屉 expectedVersion 过期 409（补 GET shot 端点 + 抽屉同步）；② 剧情/分镜模式结果误受 500 字限制；③ 抽屉保存后 dirty 基线不更新（UI 瑕疵）。

## 遗留事项

- P3 批次生产、P4 时间线/字幕/成片导出（下轮）；字段级锁定/部分采纳、道具域、提示词修订表、小说导入映射、跨项目导入、连续性自动冲突检测（均按方案明确延后）。
- 素材库「用于镜头」反查 API 已有（/story/asset-usage），素材卡片徽标 UI 未接入。
- 真实生成计费累计：V1-A 2 次（5s/720p）+ 本轮 1 次（4s/720p）。
- 改动未提交时已在本轮末尾提交（见 git log）。

## 环境注意事项

- 桌面端随本批重新打包（release/）；application-local.yml 未读取/修改。
- 优化模式走 `optimize` 场景绑定（回退通用 chat）；生成走 `video_generation` 绑定（当前 sd_2.0_special_720p）。
