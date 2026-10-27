# 会话交接：知识库显式开关与工作任务意图（2026-10-27）

> 已被 `session-2026-10-27-workspace-project-routing.md` 取代；本份保留检索开关调整记录。

## 本次改动

- 意图分类从“知识库问答、联网查询、工具任务、闲聊问答”调整为“工作任务、联网查询、闲聊问答”；分类结果不再自动开启任何检索开关。
- Qwen 与 Jev 路由均删除知识库分类和 `needs_knowledge` 输出；“工具任务”更名为覆盖代码、资料、文件和多步骤执行的“工作任务”。
- `IntentDecision` 删除 `AUTO_KNOWLEDGE` 和 `AUTO_WEB`。知识库与联网开关始终保持用户请求值，闲聊短路继续保留。
- 同步更新意图单测、指标测试、CI 意图门禁题面、评测集当前标注、设计说明与配置注释。
- 新增 bug 记录 `docs/bugs/bug-2026-10-27-intent-auto-knowledge-misroute.txt`。

## 验证结果

- 定向测试 `QwenIntentRouterTest,JevIntentRouterTest,AgentMetricsTest` 通过。
- 全量 `mvn test`：305 通过、0 失败、1 跳过。
- 评测 JSON、Python 门禁脚本和 `git diff --check` 已校验。
- `mvn -DskipTests package` 成功；本地后端已重启，`GET /api/health` 返回 `ok`。

## 环境与遗留

- 未修改任何 Flyway、数据库数据或 `application-local.yml`。
- 用户已有的 V17 修改与 `release/` 未纳入本次改动。
- 历史 baseline JSON 保留旧分类结果，仅供历史对照；当前三分类仍需调用真实模型生成新基线。
- 当前本地后端已加载本次改动。
