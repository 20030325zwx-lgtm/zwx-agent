# 对话输入框：权限等级与模型选择

日期：2026-10-28
范围：`frontend/src/components/workspace/TaskComposer.vue`、`frontend/src/views/SuperAgent.vue`、
`ManusAgentController`、`ModelCatalogService`

## 目标

对话输入框承担两类运行期决策，并且要像商业产品一样可发现、可理解：

1. 权限批准等级：本次任务允许模型执行到什么程度；
2. 模型选择：本次任务用哪个已配置的模型。

## 权限等级

沿用既有五档权限模式，原生 `<select>` 换成富菜单：图标 + 标题 + 一句说明 + 当前项勾选。

| 值 | 标题 | 说明 |
| --- | --- | --- |
| PLAN | 只读规划 | 仅允许只读工具，写操作直接拒绝 |
| BUILD | 严格 | 文件写入与执行操作都需要确认 |
| EDIT | 编辑 | 文件产出免确认，执行类工具需确认 |
| GUARDED | 守护 | 常规操作免确认，高危操作需确认 |
| YOLO | 全自动 | 全部放行，仅建议在隔离环境使用 |

选择仍存 `localStorage`（`zwx-permission-mode`），随消息以 `permissionMode` 传给后端，
由 `PermissionService` 在工具层执行。未手动选择时跟随服务端默认值。

## 模型选择

- 模型列表来自 `/settings/catalog`：只列**启用供应商下、启用且具备 `tools` 能力**的模型，
  因为智能体编排依赖工具调用；不满足条件的模型不进入选择器。
- 默认项取用户 `chat` 绑定的模型；用户切换后按会话记住（`aster.chat.model.<conversationId>`），
  新会话回到绑定默认值。这样同一会话内模型稳定，不会被全局设置改动影响。
- 消息随 `modelRowId` 传给 `GET /ai/manus/chat`；后端 `ModelCatalogService.resolveModelRow`
  校验归属（本人或系统供应商）、启用状态后返回 `ResolvedModelConfig`，图编排与闲聊直达两条路径共用，
  并通过 `attachToRun` 把 provider/model 冻结进 `agent_run`，便于回放与排查。
- 不传 `modelRowId` 时保持原有解析顺序（用户绑定 > 系统绑定 > 旧配置 > 环境变量）。

## 交互细节

- 两个入口都是输入框底部的胶囊按钮，菜单向上弹出（`bottom: calc(100% + 8px)`），
  点击外部或 Esc 关闭；权限菜单左对齐，模型菜单右对齐。
- 菜单项为 30px 图标块 + 标题 + 说明 + 勾选，与工作台其余菜单保持同一套 `--wb-*` 令牌。
- 模型名过长省略显示，hover 显示完整模型名。

## 边界与后续

- 视觉模型（只有 `vision` 能力）当前不进入对话模型列表；若后续确认其工具调用可用，再加能力标签。
- 桌面端与网页端共用同一套目录接口，配置各自独立（桌面为内置数据库）。
- 后续可把「最近使用」与「按会话模型」做进服务端，让多端同步；当前仅本地记忆。
