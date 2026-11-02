# M0 产品外壳收敛：导航、创作首页与作品列表

日期：2026-11-02
上游规划：[Aster AI 创作核心产品规划](./2026-11-02-ai-creative-core-product-plan.md) 第 13 章 M0
基线：`6c0ff57`（视频批次生产 P3 之后）

## 目标与边界

M0 只改产品外壳：让创作主线成为入口和默认落地页，把系统模块降级，并让作品列表能按类型/关键词筛选、按最近更新排序、用真实产物作封面。

**不改**：生成内核（MediaJob/批次/候选）、`CreativeProject.vue`（2135 行，M1 再拆）、数据库表结构（无迁移）。

## 一、导航与路由

`frontend/src/components/workbench/GlobalNav.vue`

- 主组：创作 `/creative`、作品 `/creative/works`、素材 `/creative/assets`、任务 `/creative/tasks`。
- 「更多」分组（视觉降权，不变的能力）：工作流、知识库、智能体、服务概览。
- 品牌区从 `div` 改为指向 `/creative` 的链接。
- **高亮改为最长前缀命中**。此前 `isActive` 用前缀匹配，`/creative/works`、`/creative/assets`、`/creative/tasks` 会同时点亮「创作」。改成先算出唯一 `activePath`（最长命中），再由各导航项比对。
- 折叠态下「更多」分组只显示小号分组名与图标，不新增二级抽屉。

`frontend/src/router/index.js`

- `''` 与 `home` 的 redirect：`/workbench` → `/creative`（用户确认：创作首页成为默认落地页）。
- 新增 `/creative/works` → `ProjectLibrary.vue`。
- `/workbench`、`/workflows`、`/knowledge-admin`、`/creative/projects/:id`、`/creative/assets`、`/creative/tasks` 全部原样保留（规划 §19.7 要求先保留旧路由，回归后再清理）。

## 二、创作首页（重写 `CreativeHome.vue`）

首屏是创作输入，不再是介绍卡片：

1. 一句想法 textarea（Ctrl/Cmd+Enter 直接开始）+ 类型分段（图片/视频/小说，漫画保持禁用）+「开始创作」。
   - 名称为想法首行前 30 字，完整想法进描述；**图片项目顺带写入草稿**，进入项目即见提示词，草稿创建失败不阻塞进入（视频/小说走各自剧情与章节域，现有草稿接口只支持 `text_to_image`）。
2. 最近作品（`limit=6`，真实封面，复用 `ProjectCard`）+「全部作品 →」。
3. 待处理：`GET /ai/creative/health` 给在途总量，`listCreativeJobs({limit:20})` 给出可点开的条目；计数为「生成中 / 最近失败 / 结果待确认」，三个数字与列表同源，不会出现「需处理 0 却列出失败项」的矛盾。
4. 次要入口：`从工作流创建`（保留原弹窗与 `buildRunForm` 推导逻辑，只降级为一行链接）、素材库。
5. 保留图片模型未绑定 banner。
6. 首页不出现工作区/会话/知识文档等统计数字。

## 三、作品列表（新增 `ProjectLibrary.vue`）

- 类型 chips（全部/图片/视频/小说）+ 搜索（名称/描述，320ms 防抖）+ 排序（最近更新/创建时间，切换即重置游标）。
- 网格卡片 `components/creative/ProjectCard.vue`：16:9 封面（图片走缩略图 blob；视频走短时效播放签名 URL，`preload="metadata"`）、类型徽标、归档标、名称、描述两行、更新时间。无产物显示「还没有产物」占位，不伪造截图。
- 游标「加载更多」，末尾显示总数；空状态区分「没有作品」与「没有匹配的作品」，均给出首要动作。
- 首页与作品页共用 `ProjectCard`，避免两套封面逻辑。

## 四、后端最小补丁（无迁移）

`CreativeProjectRepository.listByOwner` 重写为：

- 过滤：owner + 可选 `kind` + 可选关键词（`name/description ILIKE`，通配符在服务层转义）。
- 排序：`updated_at DESC, id DESC`（默认，复用 V8 的 `idx_creative_project_owner`）或 `created_at DESC`；游标按当前排序列取值。
- 封面：一条 `LEFT JOIN LATERAL` 取每个项目最新 `READY` 素材（图片优先），返回 `coverAssetId`/`coverMediaType`，避免前端按项目逐条反查。

`CreativeProjectService`：`ProjectView` 增加两个可空封面字段（仅列表填充，创建/详情不做额外查询）；`list` 新增 `(kind, q, sort, cursor, limit)` 重载，保留旧三参重载给既有调用方与测试。

`CreativeProjectController`：`GET /ai/creative/projects` 新增可选 `kind`、`q`、`sort`。

**排序语义变化**：项目列表从「创建时间」改为「最近更新」。游标 wire 格式仍是 `millis:uuid`，仅翻页边界语义变化，可接受。

## 五、与上游规划的两处刻意偏离

1. **不挂「画布」「技能」空入口。** 现有代码约定「只挂已上线能力，不做空占位入口」；画布是 M4、技能产品化是 M3，且后端目前只有 `web-research` 一个技能，没有创作类技能可展示。
2. **「工作流/知识库/智能体/服务概览」降级而不删除。** 规划 §6.1 说这些应收进设置或二级入口；直接摘掉会让已上线能力失去入口，因此收进导航底部「更多」分组。

## 六、留待后续（M1+）

- M1：`ProjectShell`、项目内 tab 路由化、镜头右侧面板、任务中心按「待处理」分组。
- M2：素材业务分类、来源链、「用于镜头」徽标（反查 API 已有但前端未接）、回收站。
- 项目封面持久化列（本版即时计算，不引入冗余列）。
- 首页「继续上次编辑位置」（目前只到作品级，不到镜头级）。
