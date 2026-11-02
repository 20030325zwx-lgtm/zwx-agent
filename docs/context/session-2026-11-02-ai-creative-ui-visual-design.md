# AI 创作 UI 视觉规划会话（2026-11-02）

## 改动内容

- 新增 `docs/design/2026-11-02-ai-creative-ui-visual-design.md`。
- 基于参考产品可见界面、用户提供的素材库截图和 Aster 当前前端实现，形成视觉定位、设计令牌、布局、核心页面、组件、状态、响应式、可访问性和实施路线。
- 更新 `docs/design/README.md` 索引。

## 关键判断

- Aster 应采用“克制、专业、媒体优先、适合长时间工作”的创作工具风格，而不是营销页、通用后台或大面积科技渐变。
- 参考产品仅用于提炼结构和交互原则，不复制品牌、素材、紫色风格、倾斜空状态图片或浮动助手。
- 第一批优先统一设计令牌与基础组件，并重构创作首页和短剧分镜；自由画布视觉开发后置。

## 验证范围

- 对照 `tokens.js`、`style.css`、`creative.css`、`GlobalNav.vue`、`CreativeHome.vue`、`CreativeAssets.vue`、`CreativeProject.vue` 和 `StoryWorkspace.vue`。
- 本次只修改文档和索引，未修改前端源码、后端、数据库或本地配置。

## 后续事项

- UI0 开始前，需要确认所有 `--sk-*` 消费者，禁止直接删除旧令牌。
- UI2 应使用真实短剧项目、候选和失败任务完成多视口截图验收。
