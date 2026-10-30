> 已被 [session-2026-10-29-ai-creative-implementation-plan.md](./session-2026-10-29-ai-creative-implementation-plan.md) 取代；以下保留首次设计时的历史基线，图片专用绑定现已实现。

# 会话摘要：AI 创作模块详细设计（2026-10-29）

## 改动内容

- 用户明确幻映 AI 为第三方，希望在 Aster 中自主开发同类能力，并授权详细设计。
- 新增 docs/design/2026-10-29-ai-creative-studio.md：范围、页面、产品流程、模块边界、供应商适配、任务状态机、拟议数据表/API、存储、Agent/工作流融合、视频画布/短剧扩展、验收与发布计划。
- 设计决策：模块化单体 + PostgreSQL 队列；先图片闭环，后视频/画布/短剧；复用登录与模型目录；不依赖幻映 AI 私有服务。
- 仅设计文档，未实施业务代码、数据库迁移、真实模型调用或服务重启。

## 验证结果

- 已核对路由、依赖、WorkflowService、模型目录、V4/V5 迁移、CurrentActor、ToolRiskCatalog、OSS 实现及现有规划。
- 现有生图借用 PURPOSE_CHAT，DashScope 同步轮询约 120 秒；工作流状态仅三种，不能直接认为具备媒体长任务恢复。
- 当前模型目录用途 chat/vision/embedding；底层 storage 是阿里 OSS SDK，MinIO 存在不等于已支持 S3。
- 文档的新增命名均明确标为拟议；不把第三方前端代码推断当作已实测能力。
- 文档结构检查通过：454 行、18 个章节，代码围栏成对、索引目标存在；git diff --check 通过。仅文档变更，未运行业务测试。

## 遗留事项

- 按设计 M0 核验真实图片模型、当前供应商协议和收费，再实施 M1–M4。
- 真实供应商凭据未知；未读取或修改 application-local.yml。
- 尚无媒体底座或 UI 实现，不能声称生图模块已经可用。

## 环境注意事项

- 实施前重新读最新会话摘要和 git 状态，重新确认迁移编号。
- 检查期间前端存在并发变动：最初 Workflows.vue 已修改，收尾时 WorkflowEditor.vue 修改及 Workflows 2.vue 新文件出现；这些均非本轮编辑，未处理。
- 保留其他会话改动；不覆盖既有产品路线图。本设计作为新增方向独立索引。
