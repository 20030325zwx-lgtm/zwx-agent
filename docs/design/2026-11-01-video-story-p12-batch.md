# 视频剧情域（P0+P1+P2）实施记录

日期：2026-11-01。基线：V1-A 批次之上（`843af7b`）。承接：[调研与实施方案](2026-11-01-video-production-management-research.md)。范围：P0 提示词优化纠偏 + P1 内容组织 + P2 剧情闭环；**P3 批次生产 / P4 成片导出未做**（明确留下轮）。

## 数据库（V17__video_story.sql，照 V13 组合外键模式）

`video_episode`（剧集：序号唯一、剧本 DRAFT/CONFIRMED 单向）→ `video_scene`（场次：目标/冲突/场前场后状态）→ `video_shot`（镜头：叙事目的/动作/运镜/对白/衔接/目标时长/prompt_text + **version 乐观锁** + 生产状态机 DRAFT/READY/GENERATING/PENDING_SELECTION/SELECTED）；复用资源 `video_character`/`video_location`（名称项目内唯一）；`video_continuity_note`（事实/状态/伏笔 + 伏笔四态）；`video_shot_candidate`（候选：GENERATING→CANDIDATE→SELECTED，**部分唯一索引保证每镜头单选**，job_id 唯一回写）；`video_asset_ref`（角色/地点参考图）。

## 后端 `creative/story/`

- `StoryContentService`：全领域 CRUD + 确认（单向）+ 剧集交换排序（同事务）+ 镜头 expectedVersion 乐观锁（409 文案指引刷新）；`workspace()` 一次取全供前端。
- **P0 `PromptOptimizationService` 重构**：请求加 `mode`（IMAGE=旧路径零迁移兼容原响应字段 / VIDEO_PROMPT / VIDEO_STORY / VIDEO_STORYBOARD）。视频模式三套系统提示词（依据调研文档：动作部位/幅度/速度、单镜头一种运镜、起始→主要→结束、衔接方向；不得改写已确认事实；缺失列为建议）；**上下文服务端按 ID 定向读取**（项目设定 + 角色/地点 + 连续性记录 + 集/场/镜头字段 + 归属校验，不信任客户端拼装）；视频模式结构化 JSON（optimizedText/changes/suggestions/missing），**解析失败如实 422，不再用纯文本兜底冒充结构**；VIDEO_PROMPT 输出受 500 字上限（与 MediaJobService 提交校验一致）。
- `ShotProductionService`：generate = 状态版本围栏占位 → 候选预建（prompt 快照冻结）→ `mediaJobService.create`（幂等键 `shot-{shotId}-{candidateId}`）→ 受理失败回滚候选与状态；上传候选（本人 READY 视频素材，显式、不计费）；select/unselect（乐观锁 + 事务内 demote 旧选定）；候选带 job 状态轻量关联。
- `ShotCandidateResumeScheduler`（5s，R02 模式）：GENERATING 且任务终态 → SUCCEEDED 回写首个 DONE 产物（镜头→待选片）/ FAILED→候选 FAILED（镜头→READY）；无产物如实失败。
- `StoryController`：`/ai/creative/projects/{id}/story/**` 全套 REST（workspace/episodes/scenes/shots/candidates/select/characters/locations/notes/refs/asset-usage）。
- 素材反查：`GET /story/asset-usage?assetId=` 返回候选使用链（集/场/镜）。

## 前端

- 新组件（`views/creative/story/`）：`StoryWorkspace.vue`（剧集列表/场次卡/分镜表/排序/确认/级联删除，GENERATING 时 3s 轮询）、`ShotDrawer.vue`（§7.2 字段分区编辑 + 优化建议**原文/新稿对比 + 可编辑采纳** + 生成按钮（默认 4 秒 + 计费提示）+ 候选区视频播放/选定/换选/删除 + 上传候选）、`StorySettingsPanel.vue`（角色/地点/连续性记录）。
- `CreativeProject.vue`：VIDEO 项目默认落「剧情」tab；快速生成优化按钮按项目 kind 自动分流 IMAGE/VIDEO_PROMPT（P0 验收点：同一输入得到不同策略）。
- 瑕疵修复：抽屉保存后 dirty 基线更新；候选/回写推进镜头版本后抽屉同步 expectedVersion（GET shot 端点）。

## 取舍（本轮明确不做，见方案 §10/§11）

字段级锁定与部分采纳（降级为对比+可编辑采纳）；道具(Prop)域；提示词修订独立表（候选 prompt 快照覆盖回看）；小说章节导入映射；跨项目导入；自动连续性冲突检测（本版只做清单展示+上下文注入）；P3/P4。

## 验证

见 [验收证据](../test/results/2026-11-01-video-story-p12.md)。真实生成严格 1 次（4s/720p，用户授权）。
