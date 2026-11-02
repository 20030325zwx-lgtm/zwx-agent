# M0 产品外壳收敛会话（2026-11-02）

## 改动内容

按上游规划 `docs/design/2026-11-02-ai-creative-core-product-plan.md` 的 M0 落地产品外壳：

- **导航**：`GlobalNav.vue` 改为创作主线主组（创作/作品/素材/任务）+「更多」分组（工作流/知识库/智能体/服务概览）；高亮改为最长前缀命中，修掉 `/creative/*` 子页同亮「创作」的问题；品牌区链接到 `/creative`。
- **默认落地**：`router/index.js` 的 `''` 与 `home` 重定向改为 `/creative`；`/workbench` 仍可访问（导航「更多」）。
- **创作首页**：重写 `CreativeHome.vue`——一句想法 + 类型分段 + 开始创作（图片项目顺带写草稿）、最近作品（真实封面）、待处理（生成中/最近失败/结果待确认）、次要工作流入口；去掉统计卡式叙事。
- **作品列表**：新增 `ProjectLibrary.vue`（类型筛选/搜索/排序/游标加载更多/空状态）与共用卡片 `components/creative/ProjectCard.vue`（图片缩略图、视频帧、无产物占位）。
- **后端**：`CreativeProjectRepository.listByOwner` 支持 `kind`/关键词、按 `updated_at` 或 `created_at` 排序、`LEFT JOIN LATERAL` 即时算封面；`CreativeProjectService.ProjectView` 增加 `coverAssetId`/`coverMediaType`；控制器增加可选 `kind`/`q`/`sort`。无数据库迁移。
- 新增 `getCreativeHealth` helper（首页待处理计数）。

实施记录：`docs/design/2026-11-02-m0-product-shell.md`。

## 验证结果

- `CreativeProjectServiceTest` 7/7（新增 kind/q/封面/排序用例）；`mvn -DskipTests package`、`npm run build` 通过。
- 浏览器 E2E（零计费）：默认落地、导航高亮、一句话创作（草稿预填）、作品筛选/搜索/空态、真实封面（视频帧+图片缩略图）、待处理计数、视频项目剧情页/小说章节页/图片素材页回归、旧入口可达、深浅色截图——全部通过。
- 证据：`docs/test/results/2026-11-02-m0-product-shell.md`。

## 遗留事项

- **M1**：ProjectShell、项目内 tab 路由化、镜头详情右侧面板、任务中心按「待处理」分组。
- 项目封面为即时计算，未落库；首页「继续上次编辑位置」只到作品级。
- 测试产物：新建了 `M0 验收：清晨海面…` 图片项目（含草稿），未删除（无删除端点，仅归档）。
- 「画布」「技能」未进导航（M4/M3 才有真实能力），与规划 §6.1 的差异已在设计文档说明。

## 环境注意事项

- 本地后端使用云端数据库；本批后端改动通过 `launchctl kickstart -k` 重启生效，未跑全量集成回归（改动不涉及 MediaJob/调度/批次）。
- 前端 dev server 由 Vite 热更新，无需重启。
- `application-local.yml` 未读取、未改写；验收只浏览数据，未触发生成计费。
- 浏览器验收后已把主题切回浅色，未改动用户偏好。
