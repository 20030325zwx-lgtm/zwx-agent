# 会话交接：权限模式分级 + 评测可复现性 + 迁移基线整合（2026-10-28）

## 本次改动

### 0. 迁移基线整合（破坏性，已清库）
- 新建 `V1__baseline.sql`：合并原 V1~V31 的最终结构，删除历史演进语句；保留注释与约束。
  不进入 Flyway 的对象维持原样（`agent_knowledge_vector` 由 PgVectorStore 启动创建）。
- 删除旧 31 个迁移文件；本地库 `DROP SCHEMA public CASCADE` 后重建，并重建 `vector`/`hstore`/`uuid-ossp` 扩展。
- 无需 seed：管理员由 `AdminUserInitializer` 创建，默认知识分类由 `AgentKnowledgeDocumentService` 按需创建。

### 1. 权限模式分级（V2）
- 新增 `PermissionMode`（PLAN/BUILD/EDIT/GUARDED/YOLO，默认 **EDIT**，与引入前行为一致）、
  `ToolRisk` + `ToolRiskCatalog`（READ_ONLY/WRITE/EXECUTE/DESTRUCTIVE，未登记工具按 EXECUTE）。
- 新增 `PermissionService`：DENY 规则 > ALLOW 规则 > 模式矩阵；run 级快照注册与释放；
  `agent_user_setting`、`agent_permission_rule`、`agent_run.permission_mode`。
- `ToolGuardHook` 确认门改为三值判定：DENY 拦截、ASK 走既有挂起链路、ALLOW 放行；
  `ToolConfirmationService` 移除自己的工具分类，只负责登记/持久化/决定状态；`required-tools`
  继续作为最低 EXECUTE 抬升项。
- YOLO 跳过计划确认门（图状态新增 `PERMISSION_MODE`）；resume 使用 run 快照。
- API：`GET/PUT /ai/settings/permission`、`POST/DELETE /ai/settings/permission/rules`；
  聊天接口新增可选 `permissionMode`；新增管理员开户 `POST /auth/users`（公共注册仍关闭，供评测预置隔离账号）。
- 前端：TaskComposer 五档选择器（localStorage 记忆）、确认横幅显示模式、`PermissionSettings.vue`
  与工作台「权限」导航。

### 2. 模型 IO 轨迹 + 评测可复现性（V3）
- 新增 `agent_llm_call`（run/阶段/模型/token/耗时/请求与响应摘要），默认只存结构摘要；
  `app.agent.observability.model-io-full=true` 时存完整载荷（单条 64KB 截断）。
- `TokenUsageAdvisor` 记录耗时与 IO；四个图节点与每个 worker 已接入（`ModelIoService`）。
- 新增 `GET /ai/manus/conversations/{cid}/runs/{rid}/trajectory`：聚合检查点、工具账本、模型调用、执行事件。
- 评测改造：`run_task_eval.py` 使用 YOLO + 隔离账号（默认 `eval-bot`，管理员开户，失败回退管理员并告警），
  写 manifest（commit、评测集 SHA、权限模式、模型快照脱敏、起止时间），失败样本落
  `evaluation/eval-sets/artifacts/`（trajectory/events/meta）；`score_task_result.py` 独立评分、可重放。

## 验证结果

- `mvn clean test`：346 通过、0 失败、1 跳过（新增 PermissionService 11、Auth 开户 2、Hook 模式 2）。
- `mvn -DskipTests package` + launchctl 重启；`GET /api/health` = ok；Flyway 仅 V1/V2/V3。
- 设置 API 冒烟：默认 EDIT、PUT BUILD 生效、规则增删正常（已恢复 EDIT 并删除测试规则）。
- 评测：三路径全过（task-001 9.7s、task-002 123.1s、task-003 2.4s）；隔离账号 `eval-bot` 由管理员开户成功；
  manifest 含 commit/模型快照；轨迹验证 5 次调用（planner/worker/verifier/aggregator）；
  失败留证演练产出 trajectory.json + events.json + meta.json。
- 前端 `npm run build` 通过；`/` 与 `/permission-settings` 均返回 200。

## 遗留事项

- 权限：`PLAN` 模式目前只影响工具执行，不改变 planner 行为；如需“先只读分析再切换执行”，可后续加模式切换对话。
- 评测：task-002 仍约 2 分钟（真实模型 + 多步），YOLO 省掉的是计划门等待；可考虑用例级超时与并发。
- 轨迹：`model-io-enabled` 默认开启（摘要级），每个 run 会增加 N 条 INSERT；需要极致成本可关闭。
- 后续阶段（未做）：design token 与暗色主题、记忆对照评测与向量化、任务中心与后台并行、子代理上下文隔离。

## 环境注意事项

- **`frontend/.env.local` 中的管理员密码与当前库中 admin 账号不一致**（用 admin/admin123 可登录）。
  评测脚本已改为候选凭据依次尝试 + 管理员开户隔离账号，不依赖该文件；不要覆盖该文件。
- 迁移文件增删改后必须 `mvn clean` 构建，否则残留资源导致 Flyway 版本冲突（见
  `docs/bugs/bug-2026-10-28-stale-flyway-resources.txt`）。
- 本地出现过 Finder 副本垃圾 `ModelIoService 2.java` 导致“类重复”编译错误，已删除；
  如再遇到编译报同类名重复，先检查 `* 2.java` 副本。
- 本地库已重建：历史会话/知识文档/记忆已清空；如需知识库用例，请重新上传文档。
- 评测产物：`evaluation/eval-sets/task-eval-result.json` 与 `artifacts/` 已加入 .gitignore。
