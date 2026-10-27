# 评测集（evaluation/eval-sets）

## 当前版本

- 评测集：[knowledge-intent-eval-v1.json](./knowledge-intent-eval-v1.json)（27 例：意图 12 + RAG 15；2026-10-27 起意图仅分为工作任务、闲聊问答，联网查询分类删除——联网由用户开关与编排提示词决定）
- 基线：
  - [baseline-2026-10-26.json](./baseline-2026-10-26.json)：r1（intent-011 改标前），意图 11/12、RAG 15/15
  - [baseline-2026-10-26-r2.json](./baseline-2026-10-26-r2.json)：r2（旧三分类标注下），**意图 12/12、RAG 15/15**
  - [baseline-2026-10-27-two-class.json](./baseline-2026-10-27-two-class.json)：r3（当前两分类标注下，意图部分；RAG 未重跑）
- 基线脚本：[run_baseline.py](./run_baseline.py)（意图走 DashScope 兼容模式 + QwenIntentRouter 同款系统提示词；RAG 走 `POST /ai/agent-knowledge/documents/{id}/verify-retrieval`；`python3 run_baseline.py <输出路径>` 可指定结果文件）

## 用途

1. **意图闲聊短路的门禁**：意图用例必须达到约定通过率，通过数回落需先复盘题面再恢复短路。
2. **意图题面校准**：失败用例暴露 criteria 措辞边界，修改 `QwenIntentRouter`/`JevIntentRouter` 的题面后重跑对比。
3. **RAG 检索策略对比**：改造检索（混合检索、重排）前后重跑 RAG 用例，命中/未命中对比是改造有效性的直接证据。
4. **回归门禁**：改动意图提示词、检索参数后必须重跑并附结果。

## 语料与范围

- `test` 范围：人文地理语料 31 篇（源文件在 `data/knowledge/super-human-geography`，已入库，幂等键 `n8n-eval-corpus-*`，自动发布，共 218 切片）。RAG 用例 rag-001~012。
- `super` 范围：员工手册（2024版）.pdf、G60 沪昆高速镇胜线交通事故梳理。RAG 用例 rag-013~015。

## 基线要点

- 意图平均延迟约 1.17s（qwen-plus，自报置信非校准）；r2 基线意图 12/12、RAG 15/15。
- **2026-10-27 路由口径调整**：删除知识库意图和知识需求评分，“工具任务”更名为“工作任务”；intent-011 恢复为闲聊问答。旧基线 JSON 保留当时口径，仅供历史对照，需重新生成新基线后才能代表当前分类效果。
- **2026-10-27 删除联网查询分类**：意图只分工作任务/闲聊问答；intent-003/004（天气、金价）改标闲聊问答，intent-010（竞品调研）改标工作任务。联网与否由用户开关决定，开关开启时由编排提示词按任务自行决定是否真正搜索。r3 基线以此口径重跑（见上）。
- **批 2 闲聊短路已于 2026-10-27 启用**（代码层）：命中候选且用户未勾选开关时单轮直达，跳过 RAG/联网/图编排；`app.intent.short-circuit-enabled` 可关。注意观察期局限：本地 52 次 run 均在 `intent.provider=none` 下产生，无真实路由数据，启用依据为评测门禁 12/12；provider 启用后应观察 `agent_intent_decision` 指标并复盘。
- RAG 15/15 全命中：当前向量检索 + 重排在显式事实型问题上表现良好，作为混合检索改造的对照基线。

## 维护规则

- 新增用例必须带 `notes`（判定依据或模糊点说明）；改标注要改版本号并在本文件记录变更。
- 评测集 JSON 是唯一事实来源，基线结果 JSON 与评测集版本对应，不覆盖旧基线。
- 重跑方式：`python3 evaluation/eval-sets/run_baseline.py`（需要 `application-local.yml` 中的 DashScope Key 与本地后端在线）。

## 门禁（2026-10-27 接入）

- `.github/workflows/ci.yml` 三个 job：后端 `mvn test`、前端 `npm run build`、意图评测门禁。
- 意图门禁脚本 [run_intent_gate.py](./run_intent_gate.py)：只跑意图 12 例（不依赖本地后端），通过数 < 11 时退出码 1 阻断；无 `DASHSCOPE_API_KEY` secret 时跳过不阻断（fork/PR 友好）。
- 门禁脚本与 `QwenIntentRouter` 的系统提示词必须同源：改任一侧都要同步另一侧并重跑基线。
- RAG 15 例依赖本地后端 + 已入库语料 + 真实 embedding 调用，暂不进 CI，改动检索策略时本地跑 `run_baseline.py` 并附结果。

## 任务级端到端评测（2026-10-28 接入）

- 脚本 [run_task_eval.py](./run_task_eval.py)：驱动本地后端跑真实任务，覆盖三条路径——闲聊直达（无 run/计划）、知识库单步（验收断言）、多步依赖任务（计划确认门自动授权 + 依赖波次 + 文件证据）。
- 评测集 `task-eval-v1.json`：用例带 `expect` 断言（min/max_plan_steps、has_dependencies、expect_file、verification）。
- 依赖：本地后端在线 + 真实模型；管理员凭据读 Git 忽略的 `frontend/.env.local`（与登录页自动填充同源），可用 `ASTER_USER/ASTER_PASS` 覆盖。
- 运行：`python3 evaluation/eval-sets/run_task_eval.py [输出.json] [--only <case-id>] [--timeout 600]`；单例耗时 2s~3min，适合手动回归与夜间任务，**不进 CI**。
- 2026-10-28 首次冒烟：task-001 ✅（27s，单步+验收通过）、task-002 ✅（117s，计划确认门触发+依赖波次+文件产出，验收 unverified 为一轮返工后的设计行为）、task-003 ✅（2.4s 闲聊直达）。
- 结果 JSON 含回答与计划内容，已加入 .gitignore，不提交。
