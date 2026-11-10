# 会话摘要：知识库计划 v2 阶段 1/2/3/4 可执行部分落地（2026-11-11）

## 需求

用户拍板专攻知识库并批准计划 v2（`docs/design/2026-11-10-knowledge-module-flow-map.md`），
要求按计划做完可执行部分、自验、发布服务器。阶段 5/6 按计划本身"按数据决定"不预先承诺，未做。

## 改动

阶段 2（词法，应用侧；镜像决策仍待用户）：
- `lexicalRecall` SQL 重写：ILIKE 挪进内层 WHERE（trgm GIN 可下推，原 CASE 在 SELECT 任何索引够不着）；
  平局按 length(content) 短文优先。行 mapper 提取为 `vectorRowMapper()`。
- `lexicalTerms`：新增 LEXICAL_STOPWORDS（37 个中文虚词 gram + 16 个英文短词）；
  词项上限 20→32（旧值会把 30 字问题后半句整体丢弃）。

阶段 3（检索骨架修缮，一个 PR）：
- rerank 池显式截断：`recall()` 按融合序截到 `rerankService.maxDocuments()`（新公开方法）
  并 log.info，索引映射改用截后池；不再依赖 rerank 服务内静默 limit。
- 兄弟扩展 SQL 化：元数据直查（documentId+sectionPath，ORDER BY chunkIndex，LIMIT extra+1），
  不再「拿命中块文本再向量检索」；initializer 补复合索引 idx_kv_document_section。
- 预算门禁：核心检索（向量+词法+重排）耗时超 `app.agent.rag.sibling-expansion-budget-ms`
  （默认 1800 = timeout 60%）跳过兄弟扩展，扩展不再能把整链拖到超时作废已完成工作。
- parseQuality：toScopedChunk 对 parser≠mineru 的文档块标 `parseQuality=degraded`，
  retrieveWithContext 拼上下文时追加降级解析提示行。

阶段 1（评测闭环，部分）：
- AgentMetrics 新增：ragStageMillis（vector/lexical/rerank P95 timer）、ragRerankDegraded、
  ragVectorPoolUnderfilled（HNSW 过滤取不满的观测面，评审 #2）；recall() 接线。
- InternalEvalController 新增 POST /internal/eval/retrieval：返回完整 trace 供离线评测。
- 新增 evaluation/eval-sets/run_knowledge_retrieval_eval.py（块级 recall@k 宽判/MRR/
  通道贡献/上下文含答案/负例误检/P95）+ knowledge-retrieval-golden-v1.json 框架。
  **语料待用户挑选，cases 为空，基线数字未产生。**

阶段 4（引用回图，前后端）：
- RagTraceCandidate 增 sectionPath+figureImages（评测宽判与前端都要用）。
- AgentKnowledgeChunkPreview 增 figureImages（findBlock 的 image_object_key 拆分）。
- 新端点 GET /ai/agent-knowledge/documents/{documentId}/blocks/{blockId}/image?index=n
  （readBlockImage，scope 校验 + MinIO 读取）。
- 前端：api 新增 getAgentKnowledgeBlockImage（blob）；CitationPreview 引用弹层新增原始图纸
  区块（watch 预览变化拉图，objectURL 卸载回收）；ChatRoom 传 agent-key。

## 验证

- 定向 knowledge 检索 15/15 绿（新增 RerankPool 1 + LexicalTerms 2 + SiblingExpansion 重写 3）。
- 全量 650 例：失败 9 个均为既有基线（MediaJob* 创作 + DashScope 欠费），knowledge 零涟漪。
- frontend npm run build 通过。
- macOS target/「类名 2.class」副本妖再现（surefire wrong name），mvn clean 解决；
  src 无副本，bug 记录已追加注意事项。

## 遗留

- golden set 语料与题目：等用户给 2-3 份真实 PDF（含宽表格+无图注图纸）后回填 cases、跑基线。
- 阶段 2 镜像决策（pg_bigm vs Java 分词 tsvector）仍待拍板；应用侧优化已先行。
- 阶段 4 完成标准"答案可点开原图"需真实图纸文档入库后走一遍 E2E。
- v2 runs 路径（ManusAgentController:602 附近）只取 context 丢 trace：涉及 StartCommand
  签名，超出本次范围，仍记录于设计文档 §8。
- 阶段 5/6 按计划待数据决定。

## 部署

本次批次经 deploy/scripts/deploy-to-server.sh 发布（无 Flyway 迁移，idx_kv_document_section
由 initializer 启动时自动创建）。

## 环境注意事项

- DashScope 欠费未解决：涉及真实 embedding 的 E2E（入库/检索）会失败，单测已 mock 无影响。
- 内部评测端点用 X-Aster-Internal-Token（app.internal.token）鉴权，runner --token 传同值。
