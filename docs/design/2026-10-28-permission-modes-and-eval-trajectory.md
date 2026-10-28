# 权限模式分级与评测可复现性（阶段一+二）

日期：2026-10-28
状态：已实施
关联：`docs/context/session-2026-10-28-dependency-plan-evidence.md`、ZCode 对标分析（会话内）

## 1. 背景

对照开源项目 zai-org/ZCode 的 harness 设计，本项目在两类能力上存在明显差距：

1. **权限缺少模式概念**：`ToolGuardHook` 负责安全违规预检，`ToolConfirmationService` 用一份固定的 `required-tools` 清单决定是否弹确认。没有“只读分析 / 严格 / 编辑 / 守护 / 全自动”的档位，也没有用户级 allow/deny 规则；P1 计划中的后台 Goal 模式因此缺少前置能力。
2. **评测不可复现**：`run_task_eval.py` 能跑通三路径，但结果里没有 commit/模型配置快照，评分逻辑内嵌在执行脚本里，评测使用管理员账号会污染真实记忆与会话，失败样本没有任何轨迹证据，进程重启后无法回答“通过率变化是代码变了还是模型变了”。

本阶段同时完成迁移历史整合：用户允许清库重建，把 V1~V31 合并为 `V1__baseline.sql`，新改动从 V2 开始，降低后续维护成本。

## 2. 迁移基线整合

- `V1__baseline.sql` 合并原 V1~V31 的**最终结构**，删除历史演进语句（遗留表、存量数据回填）。
- 不进入 Flyway 的对象保持原样：`agent_knowledge_vector` 由 `PgVectorStore` 启动时创建。
- 运行时初始化的数据不写 seed：管理员账号仍由 `AdminUserInitializer` 创建；默认知识分类由 `AgentKnowledgeDocumentService` 按需创建。
- 本地库执行 `DROP SCHEMA public CASCADE` 后重建（含 `vector`/`hstore`/`uuid-ossp` 扩展），Flyway 从干净基线跑起。
- 验证：331 个测试通过、health ok、admin 登录与建会话正常。

## 3. 权限模式分级

### 3.1 模式与风险矩阵

工具风险四级（`ToolRisk`）：`READ_ONLY` / `WRITE` / `EXECUTE` / `DESTRUCTIVE`，集中登记在 `ToolRiskCatalog`（未登记工具按 `EXECUTE` 处理，fail-safe）。

| 模式 | READ_ONLY | WRITE | EXECUTE | DESTRUCTIVE |
|---|---|---|---|---|
| PLAN | ALLOW | DENY | DENY | DENY |
| BUILD | ALLOW | ASK | ASK | ASK |
| EDIT（默认） | ALLOW | ALLOW | ASK | ASK |
| GUARDED | ALLOW | ALLOW | ALLOW | ASK |
| YOLO | ALLOW | ALLOW | ALLOW | ALLOW |

**默认值选择 EDIT 而非 BUILD**：引入模式前，文件写入与 PDF 产出是免确认的交付物，执行类工具才需要确认。EDIT 与该行为完全一致，避免升级后所有文件产出任务都被打断；BUILD 保留给需要严格审批的用户。

### 3.2 判定链路

`PermissionService.decide(runId, toolName)`：

1. 用户 DENY 规则命中 → DENY（优先于一切，包括 YOLO）；
2. 用户 ALLOW 规则命中 → ALLOW；
3. 模式 × 风险矩阵 → ALLOW / ASK / DENY。

规则按 run 发起时快照加载，同一 run 内判定稳定；规则 pattern 支持精确名与尾部通配（`mcp:*`）。

### 3.3 与既有链路整合

- `ToolGuardHook` 保留安全违规预检；确认门改为调用 `PermissionService`：
  - DENY → `HookAbortException` + `metrics.toolDenied(tool,"permission-mode")`；
  - ASK → 走既有 `ToolConfirmationService.markPending` + `ToolConfirmationRequiredException` 挂起链路；
  - 用户已批准/已拒绝的决定仍优先于模式矩阵。
- 未注册 run（单智能体等旧路径）不启用模式判定，行为不变。
- `ToolConfirmationService` 移除了自己的 `required-tools` 判定，只负责登记/持久化/放行状态，避免两处判定来源；`required-tools` 配置继续作为 `PermissionService` 的最低 EXECUTE 抬升项，保持既有拦截面。
- 计划确认门：YOLO 模式下 `routeAfterPlanner` 直接进入 workers，不再挂起（无人值守与评测的系统化授权）；图状态新增 `PERMISSION_MODE`，随输入与恢复注入。
- `agent_run.permission_mode` 保存 run 级快照，恢复执行沿用，不随用户设置变化。

### 3.4 存储与 API

- `agent_user_setting (tenant_id, user_id, permission_mode, json_settings, updated_at)`
- `agent_permission_rule (id, tenant_id, user_id, tool_pattern, effect, created_at)`
- `agent_run.permission_mode`
- `GET/PUT /ai/settings/permission`、`POST/DELETE /ai/settings/permission/rules`
- 聊天接口新增可选 `permissionMode`（请求覆盖用户默认）；resume 使用 run 快照。

### 3.5 前端

- `TaskComposer` 增加五档模式选择器（localStorage 记住上次选择，未手动选择时跟随服务端默认）。
- 工具确认横幅展示当前模式；`PermissionSettings.vue` 提供默认模式与规则管理，挂入工作台导航。

## 4. 模型 IO 轨迹与评测可复现性

### 4.1 agent_llm_call

每次 LLM 调用落一条记录：`run_id`、`sequence`、`phase`（planner / worker·role#n / verifier / aggregator）、`model`、token、`latency_ms`、请求/响应摘要。

- 默认记录结构摘要：消息角色、字符数、500 字符预览、工具调用名与参数预览。
- `app.agent.observability.model-io-full=true` 时记录完整载荷（单条 64KB 截断）。
- 挂点复用 `TokenUsageAdvisor`（已覆盖四个节点与每个 worker），不新增切面。
- 任何记录异常都不得影响生成。

### 4.2 轨迹导出

`GET /ai/manus/conversations/{cid}/runs/{rid}/trajectory` 聚合：run 元数据、检查点（计划/结果/验收/返工）、工具账本、模型调用、执行事件。评测失败留证与后续前端可视化共用。

### 4.3 评测脚本

- `run_task_eval.py` 只负责执行与留证：
  - 请求带 `permissionMode=YOLO`，删除“plan-proposed 后手动 resume”的代授权逻辑；
  - 隔离评测账号（默认 `eval-bot`，登录失败自动注册，仍失败回退管理员并告警）；
  - 写 manifest：git commit、评测集 SHA-256、权限模式、模型配置快照（凭据字段脱敏）、起止时间；
  - 失败样本落 `evaluation/eval-sets/artifacts/<时间>-<case>/`（trajectory.json、events.json、meta.json）。
- `score_task_result.py` 独立评分，可对历史结果 JSON 重放；新增断言：YOLO 下不应出现计划确认门。

## 5. 验证与后续

- 本次验证：`mvn clean test`、打包重启、health、权限冒烟（BUILD 弹确认 / YOLO 直跑 / PLAN 拒绝写）、三路径评测冒烟、前端构建。
- 后续阶段：design token 与暗色主题、记忆对照评测与向量化、任务中心与后台并行、子代理上下文隔离。
- 注意：`model-io-enabled` 默认开启（仅摘要），会为每个 run 增加 N 条 INSERT；如需极致成本可关。
