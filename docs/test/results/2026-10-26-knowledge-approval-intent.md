# 2026-10-26 知识审批工作台与意图分流验证

## 执行范围

1. **知识审批工作台**（前端 KnowledgeAdmin + 既有后端审批 API）：待审批筛选/徽标、REVIEWER 行内发布/退回（审核意见必填）、抽屉审计时间线、非审批人 403 提示。
2. **意图分流批 1**：建图前意图识别（qwen-plus 对照路由）自动反哺 knowledgeSearch/webSearch。
3. **质检链路修复**：worker 任务书携带用户原始请求，验证单轮通过验收。
4. **评测集基线**：`evaluation/eval-sets/run_baseline.py`。

## 结果

| 验证项 | 命令/方式 | 结果 |
| --- | --- | --- |
| REVIEWER 退回（带意见） | smokeuser POST `/reject`（reviewNote） | REJECTED + 审计 REJECT 入时间线 |
| ADMIN 发布 | POST `/publish` | ACTIVE + 审计 PUBLISH |
| 普通用户越权 | 注册临时用户 wb403probe 调 publish/reject/review-log | 全部 403（临时用户与测试文档已清理） |
| 意图自动开检索 | 不勾知识库发送"查一下先维信息的员工年假有几天" | 意图事件"已自动开启知识库检索（原为关闭）"、rag-trace 出现、命中员工手册 PDF、答案带页码引用 |
| 闲聊不误开 | 发送"你好呀" | "短路候选（批 2 未启用），维持用户开关"、无 rag-trace |
| 质检单轮通过 | 同知识问题 | 第一轮 worker 直接完整作答（带引用），"结果通过验收"，run verification=pass、零返工 |
| 评测基线 | `python3 evaluation/eval-sets/run_baseline.py` | 意图 11/12（唯一失败为刻意保留的模糊点 intent-011）、RAG 15/15，意图均延迟 1.17s |

## 说明与未覆盖风险

- 后端单测 243 个全绿（`mvn test`），前端 `npm run build` 通过；前端 UI 徽标/对话框为模板改动，构建验证 + API 层 e2e 覆盖，未做浏览器截图回归。
- 意图路由当前为 qwen-plus 对照实现（该账号 qwen-turbo 429 限流）；置信度为 LLM 自报非校准。
- 数据来源：知识问答用例使用 super 范围真实文档（员工手册 2024 版 PDF、G60 梳理表）；评测语料为人文地理演示语料（test 范围，幂等键 n8n-eval-corpus-*）。未写入任何密钥或个人信息。
