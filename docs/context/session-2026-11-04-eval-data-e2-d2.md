# 评测中台 E2 + 数据中台 D2 实施会话（2026-11-04）

前置：[session-2026-11-04-eval-platform-e1.md](session-2026-11-04-eval-platform-e1.md)（E1/D1 + 0.4.x 独立服务拆分）。
设计依据：[docs/design/2026-11-04-data-eval-platform-detail-design.md](../design/2026-11-04-data-eval-platform-detail-design.md)。

## 改动内容

### 评测中台（services/eval-service）

- **迁移 V25**（主站 Flyway 拥有）：`platform.prompt_asset` / `platform.prompt_version`；
  `eval_experiment` 加 `concurrency` / `budget` / `resume_of`；`eval_case_result` 加
  `input_tokens` / `output_tokens` / `cost_amount`；`eval_case` 加 `severity`；
  `eval_event.seq` 改全局序列（并发写安全，历史行已 setval 对齐）。
- **被测对象一等公民**：`TargetSpec`（live/model/prompt）+ `TargetResolver`（解析 Prompt 资产并冻结正文与 sha256）
  + `TargetView`（读取快照）。非 PROMPT 评估器只能 live，换模型/换 Prompt 直接 400。
- **评估器策略化**：新增 `com.zwx.aster.eval.evaluator` 包（`Evaluator` 接口 + INTENT/RETRIEVAL/PROMPT/TASK 四个实现），
  `EvalTargetService` 变成按 `EvalType` 分派的注册表。
- **成本计量**：`PricingProperties`（`app.eval.pricing`，元/千 token）+ `DashScopeClient.Completion` 返回 usage；
  PROMPT/judge 记成本，TASK 记 token（成本留空）。
- **数据集编辑闭环**：单条增删改、`PUT /datasets/{id}`、`POST /versions/{v}/fork`、tags/severity 可编辑；
  同时修复租户归属校验（见 bug 记录）。
- **执行器**：实验级并发、续跑（复制来源结果 + 跳过已完成）、单例重试、预算（maxCases/maxCost/judgeSampleRate）。
- **报告**：`/report?groupBy=tag|check|dimension`、`/trend`、`/gate`、`/export`（CSV）。
- **前端重构**：`App.vue` 拆成 shell + `ExperimentPanel` / `DatasetPanel` / `PromptPanel` /
  `CreateExperimentDialog`（三步向导：数据集 → 被测对象 → 预算）/ `ExperimentDrawer`（结果 + 报告）/ `CompareReport`；
  新增 `api.js` / `format.js` / `toast.js`。

### 数据中台（services/data-service）

- **迁移 V26**：`user_feedback` 加 `root_cause` / `assignee` / `resolution`；新增 `platform.user_signal`（append-only）。
- **反馈**：创建接口接收 `tags`；列表支持 `rootCause` 过滤；`GET /stats` 返回总量/转化率/根因分布。
- **归类**：`PUT /{id}/analysis`（8 类根因 + 负责人 + 结论），非法根因 400。
- **回流**：理由自动拼 `[根因:x] 结论 原由`，差评用例标记 `critical`。
- **隐式信号**：`POST /signals`、`GET /signals/summary?days=`、`GET /signals`（事件白名单 + 参数校验）。
- **前端**：指标卡（总量/转化率/待处理）+ 根因筛选 + 详情抽屉（回答/归类/动作）+ 根因分布表 + 隐式信号区。

### 主站

- `InternalEvalRunService` 的 TASK digest 补 `inputTokens/outputTokens/llmCalls`（来自 `agent_run.token_usage`）。

## 验证结果

- `services/eval-service`：`mvn test` 8/8 通过；`mvn package` 通过；前端 `vite build` 通过。
- `services/data-service`：`mvn package` 通过；前端 `vite build` 通过。
- 主站：`mvn -DskipTests package` 通过；本地 8123 重启后 Flyway 到 **v26**（两条迁移各 0.4s 内完成）。
- **评测中台端到端（隔离库 `zwx_agent_e2e`，真实 DashScope 调用）29/29**：
  Prompt 资产 v1 发布 → 数据集导入/单条增删改/发布 → 非 PROMPT 数据集拒绝 model 目标（400）→
  指定 `e2e-qa@v1 × qwen-plus` 实验（并发 2）成功 → 快照冻结 Prompt 正文与 sha256 →
  结果记成本与 token、judge 实跑 → 按标签/断言聚合、门禁通过/拒绝、CSV 4 行、趋势 1 点 →
  续跑复制 3 例并成功 → fork v1 回草稿。
- **数据中台端到端（同一隔离库 + 同一 eval-service）21/21**：
  反馈（标签 + run 快照）→ 根因归类 + 非法根因 400 → 按根因筛选 → 看板根因分布 →
  隐式信号上报（非法事件 400）→ 一键回流（notes 带 `[根因:prompt_weak]`、用例 critical、
  重复回流 400、状态回写 converted）→ `/data/` 200、未登录 401。
- 本机 8140 / 8141 已用新 jar 重启：`/eval/` 200、`/data/` 200，新接口未登录均 401（路由存在）。

## 遗留事项

- 前端埋点接入点（重生成/复制/导出/采纳）尚未接，`POST /signals` 目前只有 E2E 调用。
- 对话内点踩后的快捷标签 UI 未接（后端已支持 `tags`）。
- Trace 浏览 / 从 trace 建用例未做。
- 门禁给 CI 用的只读令牌未做（当前用管理员 JWT）。
- 服务器尚未发布本批（需要重打 0.5.0 镜像：backend/eval-service/data-service/frontend）。

## 环境注意事项

- 本地真实库在 `127.0.0.1:15432`（`application-local.yml`），docker 5432 是另一实例；
  E2E 用的是 5432 的 `zwx_agent_e2e` 库。
- 本地主站/eval-service/data-service 由 launchctl 管理（`com.zwx.yu-ai-agent.{backend,eval,data}`），
  启动脚本在 `~/.aster/start-{eval,data}.sh`（source `local-platform.env`）。
- 临时验证实例端口：主站 8124（管理 8136）/ eval-service 8142 / data-service 8143，测毕已停止。
- 模型价格表在 `services/eval-service/src/main/resources/application.yml` 的 `app.eval.pricing`，
  供应商调价时同步修改；拿不到价格时成本为 `null`。
