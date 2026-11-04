# 数据中台+评测中台调研会话（2026-11-03）

## 改动内容

- 纯调研，未改代码。产出调研文档：[docs/design/2026-11-03-data-eval-platform-research.md](../design/2026-11-03-data-eval-platform-research.md)。
- 追加：收到外部专家规划方案，存档至 [docs/design/plans/2026-11-03-data-eval-platform-expert-plan.md](../design/plans/2026-11-03-data-eval-platform-expert-plan.md)，并在调研文档 §7 完成融合对照（采纳/裁剪/路线图修订）。
- 核心结论：
  - 项目观测主干已完成约 80%（`agent_llm_call`、token 台账、trajectory 导出 API、`evaluation/eval-sets/` 四个脚本含 manifest 快照与隔离 eval 账号），缺「资产化（入库/版本/可查）+ 回流（用户反馈/BadCase）+ 自动化（评测平台任务化）」三层。
  - 用户反馈层完全空白（前端 feedback 只是 verifier 文本）。
  - 推荐路线 B：在现有 Spring Boot 单体内自建轻量评测中台（E1：数据集版本化/评估器框架/实验与看板）+ 数据中台回流 MVP（D1：反馈入口/BadCase 库/一键回流），Langfuse 仅作可选后置。
  - 先 E1 后 D1：评测集是 BadCase 回流的「归宿」。
- 风险：评测任务化前需先解决本地/服务器双 worker 抢跑（共用一个库）。

## 验证结果

- 源码核查：Flyway 全量表清单、`evaluation/eval-sets/` 目录、前端 feedback 字样（仅 verifier 意见）。
- 外部调研：华为云 AgentArts 评测集实践（trace 回流/字段反向设计/配比）、Langfuse/promptfoo/DeepEval/RAGAS/AgentEvals 选型、AWS AIDLC、国数局数据集指引。来源链接在调研文档 §7。

## 遗留事项

- 用户确认路线（自建 vs 引入 Langfuse）与 E1/D1 优先级后再进入方案设计。
- 未动任何代码与配置；未触碰 application-local.yml。

## 环境注意事项

- 本轮无服务变更；8123/3000 保持运行。
