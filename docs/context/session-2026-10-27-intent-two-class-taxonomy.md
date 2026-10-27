# 会话交接：意图两分类化（删除联网查询分类）（2026-10-27）

## 本次改动

- 意图分类从「工作任务、联网查询、闲聊问答」三类收敛为「工作任务、闲聊问答」两类：联网搜索不再由意图判断，完全由用户 `webSearch` 开关决定（关闭时装配层移除联网工具、Planner 提示词明示不可联网），开关开启时由编排提示词按任务自行决定是否真正搜索。这两层机制改动前已存在，本次未改其代码。
- `JevIntentRouter`/`QwenIntentRouter` 题面同步：删除联网查询 criteria/取值，明确「是否需要联网搜索不由意图判断」。
- 门禁首轮 11/12 暴露题面边界：intent-007（公司的报销流程是怎么规定的）被判闲聊问答——查内部制度若被闲聊短路直达会导致模型编造。校准题面：工作任务纳入「查找公司制度流程或文档内容」，闲聊问答限定「无需执行任务或查阅资料」。重跑 12/12。
- 评测集 `knowledge-intent-eval-v1.json`：intent-003（天气）、intent-004（金价）改标闲聊问答，intent-010（竞品调研）改标工作任务，intent-007 补充判定依据；`run_intent_gate.py`/`run_baseline.py` 系统提示词与 QwenIntentRouter 保持同源。
- 文档同步：`docs/design/2026-10-25-jev-intent-routing.md`（状态行、§3 题面、§4 决策表删联网查询行、2026-10-27 调整段）、`docs/ROADMAP.md` 意图分流两处、`application.yml` 意图注释、`evaluation/eval-sets/README.md`（含 r3 基线记录）。

## 验证结果

- 定向测试 `QwenIntentRouterTest,JevIntentRouterTest,AgentMetricsTest` 通过。
- `mvn clean test`：310 通过、0 失败、1 跳过。
- 意图门禁 `run_intent_gate.py`（qwen-plus，新题面）：**12/12**，结果存 `evaluation/eval-sets/baseline-2026-10-27-two-class.json`（r3，仅意图部分，RAG 未重跑）。
- `mvn -DskipTests package` 成功；后端经 launchctl 作业 `com.zwx.yu-ai-agent.backend` 启动，`GET /api/health` 返回 `ok`。

## 环境与遗留

- 发现后端当时未在运行且 launchctl 作业不存在，已按 AGENTS.md「macOS Persistent Start」重新 `launchctl submit` 注册并启动（日志 `/tmp/yu-ai-agent-backend.log`）。
- r2 及更早基线 JSON 为旧口径，仅供历史对照；RAG 15 例未随本次重跑（未改检索），如需完整 r3 基线可本地跑 `run_baseline.py`。
- 意图 provider 仍默认 `none`，短路行为不变；provider 启用后建议观察 `agent_intent_decision` 指标并复盘天气/金价类问题被短路直达时的回答质量（未开联网开关时应如实说明无实时数据）。
- 未修改 Flyway、数据库数据或 `application-local.yml`；门禁脚本只读取 local yml 的 api-key 行，未展示密钥。
