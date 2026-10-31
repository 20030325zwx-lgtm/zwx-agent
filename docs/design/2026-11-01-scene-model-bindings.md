# 场景级模型绑定：专用覆盖通用（2026-11-01）

> 用户诉求：模型设置里能为不同模块分别指定模型；**专用绑定覆盖通用绑定，未绑定回退通用**。

## 方案

复用 `model_binding` 表（唯一键 `(scope_type, owner_id, purpose)`），新增三个场景 purpose——**数据层零迁移**：

| purpose | 场景 | 覆盖的调用点 |
| --- | --- | --- |
| `chat` | 对话模型（通用默认） | 所有文本场景的兜底 |
| `novel` | 小说创作（文本） | 剧情走向建议（NovelPlanningService）；工作流 `llm_text` 节点声明 `scene=novel` 时（小说模板正文/概要，V16 v2） |
| `agent` | 智能体对话 | 工作台对话默认模型（ManusAgentController.chatSelection 无手动选择时） |
| `optimize` | 提示词优化 | 创作台提示词改写（PromptOptimizationService） |
| `vision` / `image_generation` | 视觉 / 图片生成（既有） | 不变 |

解析链：`resolveWithFallback(userId, purpose, fallback)` = 场景绑定存在且可用则用之，否则回退通用 chat
链（用户绑定 → 系统绑定 → 旧配置/环境变量）。绑定变更 bumpRevision 已清缓存，无需额外失效。

## 实现

- `ModelCatalogService`：PURPOSE_NOVEL/AGENT/OPTIMIZE 常量入白名单（setBinding/clearBinding 即可接受）；`resolveWithFallback`（catch IllegalArgumentException/IllegalStateException 后回退——legacy `descriptor()` 对未知 key 抛 IllegalArgumentException 已确认）。
- `WorkflowService` llm_text：可选 `scene` 参数，`novel` 走场景回退，缺省/其他值保持通用（旧模板零影响）。
- V16：`novel-chapter` 插入 v2（jsonb_set 给 draft/summarize 节点 params 加 `"scene":"novel"`）；运行绑最新版本自动生效。
- `ModelCatalogSettings.vue`：绑定区 6 行（对话/小说创作/智能体对话/提示词优化/视觉/图片生成），场景行带
  desc 与「未绑定 · 使用通用对话模型」文案，清除按钮语义为「恢复通用」。
- `nodeCatalog.js`：llm_text help 补 scene 说明。

## 验证（2026-11-01）

- 设置页 6 行渲染正确（DashScope 官方 qwen-plus 为通用对话；三个场景行显示回退文案）。
- **专用覆盖**：把「小说创作」绑定到 KKAI `gpt-image-2.5-sunburst` → AI 建议调用 → 后端日志
  `剧情建议生成失败：400 - This model is not supported on the Chat Completions endpoint`
  ——走的是专用绑定而非回退 ✓。
- **未绑定回退**：清除 novel 绑定 → AI 建议成功出 3 组候选（通用 qwen-plus）✓。
- V16 生效核对：novel-chapter v2 的 draft 节点 `scene=novel`。

## 边界与后续

- 中转站对模型名宽松路由时，正向绑定验证无法区分用了哪个模型（本次用反向验证补位）。
- bindingOptions 仍是全量启用模型（含图片模型）——给图片模型误绑对话场景会运行时报供应商错误（错误信息已如实透出）；
  后续可按能力过滤下拉。
- 不做：按项目/按模板粒度绑定、embedding 场景。
