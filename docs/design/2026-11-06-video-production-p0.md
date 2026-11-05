# 视频创作整改 P0 实施记录（2026-11-06）

依据 `2026-11-05-video-creation-remediation-requirements.md`（下称「需求书」）实施 P0。
本文记录已落地的实现与边界；P1/P2 未做，界面按需求要求不承诺未实现能力。

## 已交付（P0 范围）

### 后端

| 项 | 实现 | 说明 |
| --- | --- | --- |
| 迁移 V44 | `video_storyboard_draft` 表 | 草稿确认前与正式镜头隔离；`status ∈ READY/CONSUMED/DISCARDED/PARSE_FAILED`；rows JSONB + raw_text |
| 结构化拆镜 | `PromptOptimizationService.storyboardDraft()` | 新模式 `VIDEO_STORYBOARD_DRAFT`，输出可校验行集合（1–40 行，时长 clamp 1–60），解析失败如实返回 raw |
| 草稿服务 | `creative/story/StoryboardDraftService` | `ensureShortfilm`（D02 幂等默认剧集/场次）、`generate`、`get`、`adopt`、`discard` |
| 采纳语义 | 条件状态转移 | `UPDATE … SET status='CONSUMED' WHERE id=? AND status='READY'`：双击/重试/并发只写一次（A03）；来源版本不一致 409，显式 force 才放行 |
| 替换模式 | `REPLACE_UNGENERATED` | 只删 `status IN (DRAFT,READY)` 且无候选的镜头，保留数如实返回 |
| 镜头排序 | `StoryContentService.moveShot` | 同场次相邻交换；经临时号（1_000_000 段）中转避免 `uq_video_shot_number` 立即唯一约束冲突；镜头 ID/候选关系不变（A04） |
| 插入指定位置 | `createShot(..., afterShotId)` | 两段式整体平移（+1_000_000 / -999_999），实现「插入当前镜头之后」 |
| moveEpisode 修复 | `swapEpisodeNumbers` | 原实现两条 UPDATE 直接互换会撞 `uq_video_episode_number` 唯一约束（潜在 bug，见 bug 记录），改为同样的临时号中转 |
| 项目设定 | `SETTINGS_KEYS` + `videoStyle/videoAspect/videoTargetSeconds` | 服务端校验：画风必须是 `ImageStyleCatalog` 编码、画幅 16:9/9:16/1:1、时长 10–600；`GET/PUT /ai/creative/projects/{id}/settings` |

新端点（StoryController，归属校验沿用 CurrentActor）：

```
POST /ai/creative/projects/{id}/story/shortfilm
POST /ai/creative/projects/{id}/story/storyboard-drafts        # 同步 LLM 生成
GET  /ai/creative/projects/{id}/story/storyboard-drafts/{draftId}
POST /ai/creative/projects/{id}/story/storyboard-drafts/{draftId}/adopt
POST /ai/creative/projects/{id}/story/storyboard-drafts/{draftId}/discard
POST /ai/creative/projects/{id}/story/shots/{shotId}/move      # {direction}
POST /ai/creative/projects/{id}/story/scenes/{sceneId}/shots?afterShotId=
```

### 前端

- **导航收敛**：`NAV.VIDEO = [制作(production), 画布(canvas)]`，工具区（设定/素材/任务）以分隔线降级展示；
  `defaultTab(VIDEO) = 'production'`；story/storyboard/settings 旧路由保留（深链接可达，仅从导航移除）。
- **`ProjectProductionTab.vue`**：五阶段栏（数字圆点+完成勾+缺什么提示，完成度按实际数据计算）+
  摘要行（当前集 · N 镜头 · 目标时长 · 已选 x/y）；`?stage=` 深链接。
- **阶段组件**（`components/creative/production/`）：
  - `StyleStage`：复用 `ImageStyleCenter`（`has-reference=false` 隐藏「跟随原片」），保存到项目设定；
    显示影响镜头数与旧候选保留说明。
  - `StoryStage`：单编辑器（绑默认剧集 script）+ 800ms 自动保存 + `整理故事`（VIDEO_STORY 建议稿对比采纳）+
    `生成分镜草稿`；系列结构按需展开内嵌 `ProjectStoryTab`；脏内容不被轮询覆盖。
  - `DraftReviewDialog`：行内编辑/增删/上下移，合计时长与目标偏差；追加/替换未生成两种模式；
    PARSE_FAILED 显示原始输出 + 复制；采纳失败按服务端文案分流（已采纳/故事已修改）。
  - `ShotBoardStage`：内嵌 `ProjectStoryboardTab`（分镜/视频两阶段复用，见需求 §11：阶段栏是导航非互斥向导）。
  - 成片阶段：空态说明（下一期提供），不放假入口。
- **`ShotInspector`**：800ms 自动保存（保留手动保存与 dirty 门禁）；头部 ↑/↓ 排序与「＋在此镜头后插入」。

## 实测反馈修正（2026-11-06 同日）

- 保存画风后停在原地没有「下一步」：保存成功后自动进入下一个未完成阶段；
  阶段卡右上主行动在已保存态切换为「下一步：写故事」。
- 制作视图与画布割裂感：画布图片/视频节点的画风默认继承项目画风（节点单独选择即覆盖，
  图片有参考图仍优先「跟随原片」）；分镜/视频阶段顶部注明与画布的关系并提供「在画布中编排」；
  制作页背景统一为 `--wb-bg`，与画布同一张底。

## 未做（后续阶段）

- P1：生成预检对话框、统一任务摘要、旧设定「较早设定」标记贯通、镜头级风格覆盖。
- P2：成片拼接导出（ffmpeg 运行环境、成片任务域）——成片阶段当前为空态。
- P3：画布镜头映射、双视图同步、业务节点。

## 验证

- 后端：`StoryboardDraftServiceTest`（采纳幂等/替换范围/来源版本守卫/短片幂等）、
  `PromptOptimizationModesTest`（草稿解析与 clamp/失败如实报错）、`CreativeProjectSettingsTest`（设定校验）通过；
  creative 模块全量测试通过（`ToolGuardHookTest`/`MediaJobSchedulerTest` 为 HEAD 既有失败，与本轮无关）。
- 前端 `npm run build` 通过；Vite dev 对 5 个新组件均可编译。
- V44 已随本地重启应用（Flyway：now at version v44，2.0s）；迁移前已 `pg_dump` 备份（backups/zwx-agent-pre-V44-*.dump）。
- 端到端（登录态）：新建 VIDEO 项目 → 默认进制作 → 画风保存 → 故事自动保存 → 生成分镜草稿 →
  评审/确认追加 → 分镜阶段可见镜头 → ↑/↓/插入 → 重复采纳返回 409 → `/story`、`/storyboard?filter=pending` 深链接可用。

## 注意

- 本地后端经 SSH 隧道连接库（127.0.0.1:15432 → zwx_agent）；重启即应用迁移，迁移前先备份。
- 剧集/场次没有独立 version 列，来源版本用 `updatedAt` 纪元毫秒代理（`source_episode_version`）。
- 草稿生成是同步 LLM 调用（十几秒），前端按钮有进行中状态；失败时原故事与镜头不变（A02）。
