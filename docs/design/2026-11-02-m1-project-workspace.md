# M1 短剧项目工作区产品化：实施记录

日期：2026-11-02
上游文档：
- [Aster AI 创作核心产品规划](./2026-11-02-ai-creative-core-product-plan.md) §13 M1
- [Aster AI 创作 UI 视觉与交互设计规范](./2026-11-02-ai-creative-ui-visual-design.md) UI0/UI2
基线：`0a8a97f`（M0 产品外壳之后）

## 目标

把已实现但"埋在表单里"的视频能力变成可感知的制作工作面：项目内左侧导航、剧情与分镜分区、媒体优先镜头卡、右侧镜头检查器、候选网格选片，并把设计令牌收敛到一套。

**不改**：生成内核（MediaJob/批次/候选状态机）、数据库业务表结构（只加索引）、计费路径。全程零计费验证。

## 一、UI0 设计令牌（`design/tokens.js`、`style.css`）

- 颜色按规范 §5.1/§5.2 换值：浅色 `#F7F7F8` 页面底 / `#F1F1F3` 导航 / `#E3E4E7` 边框 / `#2563EB` 品牌；深色中性炭黑。
- 圆角收敛为 6/8/10；阴影只保留浮层用途（`--wb-shadow-md/lg`）。
- 去掉 `--wb-bg-accent` 径向渐变与 `body` 的两层径向渐变（规范 §5.1）。
- 新增排版令牌（page/section/panel/body/compact/caption + 行高）、4px 间距令牌、媒体舞台 `--wb-media-bg` 与焦点色。
- 收藏反馈从粒子爆炸改为图标短暂缩放（`.is-burst`，遵守 `prefers-reduced-motion`）。
- `--sk-*` 只做中性化，不删除：登录、知识库等旧页面仍消费它，规范要求逐页迁移后再清理。

## 二、项目壳与分区（`views/creative/project/`）

`CreativeProject.vue`（2135 行）已删除，替换为：

| 文件 | 职责 |
| --- | --- |
| `ProjectShell.vue` | 项目头（返回作品/名称/类型/进度/刷新）、左侧项目导航（200px，<1100px 收 56px）、任务详情抽屉、全局 toast；持有项目、任务、剧情三份共享状态 |
| `ProjectStudioTab.vue` | 快速生成（原 studio tab）：草稿自动保存、优化、生成、单条任务卡 |
| `ProjectStoryTab.vue` | 剧情：剧集 + 场次（移除镜头表），保留优化剧情/细化分镜 |
| `ProjectStoryboardTab.vue` | 分镜：进度筛选、批次面板、媒体优先镜头卡、检查器 |
| `ProjectSettingsTab.vue` | 设定：角色/地点/连续性（复用 `StorySettingsPanel` 的 embedded 模式） |
| `ProjectChaptersTab.vue` | 小说章节（原 chapters tab） |
| `ProjectWorkflowTab.vue` | 工作流运行（原 workflow tab） |
| `ProjectTasksTab.vue` | 任务记录（原 tasks tab，只读 Shell 的 jobs） |
| `ProjectAssetsTab.vue` | 项目素材（原 assets tab + 对比/海报/预览浮层） |
| `ProjectEntry.vue` | 空路由占位，Shell 拿到项目类型后替换为默认分区 |

子路由：`/creative/projects/:id/{studio|story|storyboard|settings|chapters|workflow|tasks|assets}`。
默认分区按类型：VIDEO→story、NOVEL→chapters、IMAGE→studio。

共享状态集中在 `composables.js`：
- `useProjectJobs`：任务列表 + 详情 + 2.5s 轮询（有在途任务才跑）+ 取消/重试/恢复入库，Shell 单例。
- `useStoryWorkspace`：一次拉全量 workspace，派生出 shots/candidatesByShot/progress，Shell 单例；3s 轮询只在有生成中镜头/候选时启动。
- `useWorkflowList`：工作流模板列表缓存（快速生成与写章节共用）。
- `studioSeed`：素材「继续创作」到快速生成的表单交接（尺寸/水印不在草稿里，必须显式传）。

**旧链接兼容**：`?tab=xxx&runId=&jobId=` 由 Shell 统一重定向到子路由并保留参数；
M0 里 `CreativeHome` 的任务链接（`?tab=tasks&jobId=`）也因此真正生效，`jobId` 会打开任务详情抽屉。

## 三、分镜页（本次重点）

- 顶部：进度（在项目头：镜头/已选片/待选片/阻塞）+ 筛选（全部/待选片/生成失败/缺提示词，带计数）+ 场次筛选 + 全选可生成。
- 筛选状态与 URL 同步（`?filter=pending`、`?scene=<id>`），项目头的「待选片」直接深链到筛选后的分镜页。
- 批次面板就地展示在列表上方：分项计数、完成率、停止（只停未提交）、重试失败项。
- 镜头卡（`components/creative/project/ShotCard.vue`）：16:9 预览（已采用候选视频帧）、镜号+场次、内容状态徽标、
  已采用/待选徽标、景别/运镜/时长、阻塞原因（缺提示词/上次失败）、单一主操作。
- 媒体地址由分镜页统一解析（`videoUrlFor`），避免每张卡各自请求与重复缓存。

## 四、镜头检查器（`story/ShotInspector.vue`）

- 替换 `ShotDrawer.vue`：≥1100px 为右侧固定检查器（420px，不遮挡、不阻断列表操作），<1100px 退化为覆盖抽屉。
- 分区顺序按规范 §8.5：候选（2 列 16:9，已采用用边框+勾选文字三重表达，失败候选给原因与重试）→ 提示词（优化/采纳/保存/生成）→ 剧情意图与摄影字段。
- 候选卡显示分辨率与时长：后端在候选查询里 `LEFT JOIN creative_asset` 返回 `asset` 摘要。

## 五、后端改动（无业务表变更）

1. `VideoShotCandidateRepository.listByShotWithJob` 增加素材摘要 join；`CandidateWithJob`、`ShotProductionService.CandidateView` 增加 `asset`。
2. `StoryContentService.workspace` 的 `candidateCounts` 改为从已加载候选分组统计，去掉每镜头一次 `countByShot` 的 N+1。
3. `V19__creative_workspace_indexes.sql`：`video_scene/video_shot/video_shot_candidate/video_asset_ref/media_job` 的项目级索引，只加索引。

## 六、与规范的取舍

- **项目导航保留「素材/任务」**：规范 §6.3 说项目导航不重复全局导航的"作品/素材/任务"，§8.3 又把它们列为项目分区。
  这里按 §8.3 保留为项目内视角（同一页面内切换，不丢上下文），并去掉"作品"这一层（返回按钮已承担）。
- **概览分区延后**：规范 §8.3 列了"概览"，本批未做；进度与待选片入口已提到项目头，先保证「剧情→镜头→生成→选片」闭环。
- **移动端只保证可查看**：390 宽未做逐像素验收，<900px 时导航收起、检查器覆盖、网格单列。
- **技能/画布**仍不入口（M3/M4 才有真实能力）。

## 七、后续

- M2 素材中心：业务分类、来源链、使用位置反查（含角色/地点引用，需要新端点与 `video_asset_ref(asset_id)` 反查）。
- UI3 任务中心：按「生成中/待选片/失败」分组的列表 + 详情；需要 `jobs` 支持 status 过滤与 `statusCounts`。
- 概览分区、项目卡片进度统计（可用一个 `story/summary` 聚合端点）。
- 剧本导入（TXT/Markdown）与连续性的自动冲突检测。
