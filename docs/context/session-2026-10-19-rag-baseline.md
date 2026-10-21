# 会话摘要：2026-10-19 RAG 生产基线

## 改动内容

- 私有知识检索新增 `KnowledgeRetrievalOutcome`，区分 `SUCCESS`、`NO_HIT`、`TIMEOUT`、`OVERLOADED`、`FAILED`；空上下文不再一律被误认为无命中。
- 检索新增 scoped lexical recall 与向量召回的 RRF 融合；rerank 仍是最终精排。词法召回限于当前租户、Agent 与 ACTIVE 文档，适合精确制度词、编号和产品代码的补充。
- 私有知识上下文明确标注为不可信资料，禁止将资料内容当系统指令；要求关键结论在生成中以 `[文件名#切片号]` 引用。
- 分块保留 PDF 页码、父文档/逻辑文档及前序 chunk metadata；chunk ID 改为稳定的 document+index，避免相同正文 chunk ID 碰撞。
- 新增 V9：失败索引记录尝试次数与下次重试时间；启用定时任务，最多自动恢复三次暂态失败。
- 新增合成评测资料：完全虚构的 Acme Nebula / 星河云图 2026 年度奖金制度，含可回答与不可回答用例；新增 Recall@K 与 MRR 的纯指标实现。
- 设计说明：`docs/design/2026-10-19-rag-production-baseline.md`。

## 验证

- `mvn -q -Dtest=AgentKnowledgeRagServiceVersionFilterTest,MyTokenTextSplitterTest,RagEvaluationMetricsTest test` 通过。
- `mvn -q -DskipTests package` 通过。
- `git diff --check` 通过。
- 后端由 launchctl 重启，`GET http://127.0.0.1:8123/api/health` 返回 `ok`。
- Flyway V9 已在本地 PostgreSQL 成功应用。

## 遗留事项

- 当前 lexical recall 为受限候选集上的轻量匹配，不是大规模中文全文检索；数据量增长后应接入中文分词能力或专用搜索服务，并用离线评测证明收益。
- 当前“引用强约束”是上下文合同和显示格式，尚未实现独立 answer verifier / citation entailment 校验，不能宣称生成回答已被强制验真。
- 合成评测集只验证框架；真实质量基线需业务方提供或批准脱敏的 50-100 条问题、标准证据和不可回答问题。
- 未提交本轮 RAG 改动。用户原有未跟踪文件 `docs/design/2026-09-28-deepseek-harness-vs-yu-agent.md` 未修改、未加入版本控制。

## 环境注意事项

- `src/main/resources/application-local.yml` 未读取、编辑或提交。
- PostgreSQL、MinIO 仍由 OrbStack 运行；后端使用 launchctl 管理。
