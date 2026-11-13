# 设计：知识向量存储迁移到 Weaviate（2026-11-12）

## 1. 背景与目标

原方案：Spring AI `PgVectorStore`（1536 维 DashScope 向量、COSINE、HNSW）自建表
`agent_knowledge_vector`（`id uuid, content text, metadata jsonb, embedding vector`），
检索管线 `AgentKnowledgeRagService`：向量召回（`similaritySearch` + 过滤表达式树）→
自研词法召回（中文 2-gram ILIKE，trgm GIN）→ Java 侧 RRF（k=60）→ DashScope gte-rerank-v2
→ 兄弟扩展（同文档同章节 SQL）。

本次按《Weaviate 优势说明》《pgvector 迁移 Weaviate 实施方案》把向量检索层整体迁到
**Weaviate 单节点（Docker）**，并**充分利用其原生 Hybrid Search**（向量 + BM25 服务端融合）。
用户明确不考虑迁移成本：不走双写/灰度，直接切换 + 全量 reindex 重新向量化；
旧向量表原样保留作为回滚底座。

**PostgreSQL 仍是业务库**（document/block/category/run/review 等关系表、Flyway 迁移均不动），
只挪向量数据。

## 2. 关键决策

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| 接入方式 | 自研 `KnowledgeVectorStore` 端口 + `io.weaviate:client6` 6.3.2 | Spring AI 官方 WeaviateVectorStore 把 metadata 存成单 JSON 字符串，无法支撑 typed 属性过滤、chunkIndex 排序、BM25 属性选择、按过滤删除等运维面 |
| 混合检索 | Weaviate 原生 Hybrid Search（`FusionType.RANKED` = 服务端 RRF k=60，`alpha` 可配） | 用户要求充分利用原生混合检索；RANKED 与原 Java RRF 同公式同尺度，trace 的 fusionScore 连续 |
| 词法通道 | BM25，`text` 属性 `tokenization=gse`（中文词典分词） | 替代 2-gram ILIKE；社区反馈 gse 混排中英文可能有怪癖，`app.weaviate.bm25-tokenization` 可切 trigram |
| 隔离模型 | 单 Collection + `tenantId/agentKey/lifecycleStatus` 属性过滤 | 租户量小、与现有安全模型（表达式树 + FilterSafetyTest 回归）1:1 对应；Weaviate 多租户（per-tenant shard）留待规模扩大后评估 |
| 桌面端/CI | `app.vector-store.provider=pgvector` 保留原路径 | 桌面内置 pgserver（`desktop/scripts/prepare-pgserver.sh`）无法打包 Weaviate；CI 不加新容器；一行配置可回滚 |
| 分数语义 | similarity = 1 − cosine distance（nearVector 路径） | 与 pgvector 的 cosine_distance → similarity 换算一致，`similarityThreshold` 配置不用动 |
| 确定性 ID | 沿用 `UUID.nameUUIDFromBytes(documentId\|chunk\|n)`（v3 形态） | trace/citation 稳定；Weaviate 接受 RFC 4122 格式 UUID（官方推荐确定性 UUID v5）；`WeaviateUuid` 兜底非法字符串 |

## 3. 架构

```
AgentKnowledgeRagService / AgentKnowledgeDocumentService
        │  依赖端口（bean 名保持 agentKnowledgeVectorStore）
        v
KnowledgeVectorStore（extends Spring AI VectorStore）
        ├── WeaviateKnowledgeVectorStore   ← 服务端/本地开发（默认）
        │     similaritySearch  : nearVector + where 过滤（score=1−distance）
        │     hybridSearch      : 原生 Hybrid（RANKED RRF，alpha=app.weaviate.hybrid-alpha）
        │     bm25Search        : BM25（gse）+ where 过滤
        │     fetchSiblings/listByDocument : 纯取回 + SortBy chunkIndex
        │     findById          : fetchObjectById + scope 校验
        │     updateLifecycleStatus : where 取 ID + 逐对象 PATCH
        │     deleteByDocumentId    : deleteMany(where)
        └── PgKnowledgeVectorStore         ← 桌面端/CI/回滚
              包装 PgVectorStore（向量 CRUD + ANN）+ 原直查 SQL 逐字搬入；
              hybridSearch = 阈值预筛向量池 + 词法池 + ReciprocalRankFusion（同公式）
```

检索管线（recall）变化：`向量召回 → 词法召回 → Java RRF` 改为
`向量召回 → 词法召回 → hybridSearch 融合池`。向量/词法两路保留（四通道 trace 的
vectorRank/lexicalRank 与 rerank 不可用时的 fallback 依赖它们），融合池来自原生 Hybrid；
一次 recall 共 3 次查询 + 1 次 embedding（查询向量在 store 内做 LRU 缓存复用）。
新增 `metrics.ragStageMillis("fusion", …)`；`RagRetrievalTrace` 记录结构与前端检查器不变。

## 4. Collection schema（AgentKnowledgeChunk）

- 向量：`VectorConfig.selfProvided(default)` + `Hnsw(Distance.COSINE)`，应用侧传向量，无 vectorizer。
- `text`：`tokenization=gse`、`indexFilterable=false`（BM25 检索面）。
- 过滤面 text（`indexSearchable=false`）：tenantId、agentKey、lifecycleStatus、documentId、
  parentDocumentId、logicalKey、parentLogicalKey、sectionPath、sourceType、blockId、filename、
  objectKey、regionLabel、tableCaption、caption、bbox、figureImages、figureCaption、figureType、
  parseQuality、chunking、structuredContentType、parser、documentType、chunkRole。
- 排序/范围 int：chunkIndex、pageNumber、rowStart、rowEnd、versionNo、figureFragments、
  chunkSchemaVersion、previousChunkIndex、doclingChunkIndex、tokenCount；bool：figureTruncated。
- `extraMeta`：未入白名单的元数据键（headings/pageNumbers/docItems 等）序列化为 JSON 文本，
  读取时展开回 metadata（白名单键优先），保证元数据往返不丢。
- 过滤翻译：`WeaviateFilterConverter`（EQ/NE/IN/NIN/GT/GTE/LT/LTE/AND/OR/NOT → 原生 where；
  不支持面直接抛异常，fail closed）。

## 4.5 嵌入模型（2026-11-13 增补：免费离线方案）

`app.embedding.provider` 切换文本嵌入模型（知识向量写入与查询共用）：

| provider | 模型 | 维度 | 成本/网络 |
|---|---|---|---|
| dashscope（默认，桌面/回滚） | text-embedding-v3 | 1536 | 云端计费（当前账户欠费） |
| ollama（服务端/本地推荐） | qwen3-embedding:0.6b | 1024 | 本地免费，数据不出服务器 |

- 装配：不注册自建 EmbeddingModel（DashScope/Ollama 自动配置 bean 均为 @Primary，按类型注入会
  多 primary 歧义），由 `AgentKnowledgeVectorStoreConfig` 按 provider 显式取 bean 名
  （`dashscopeEmbeddingModel` / `ollamaEmbeddingModel`），Ollama 模型名走
  `spring.ai.ollama.embedding.model`。
- 向量化语义：知识库 add() 只嵌入纯正文（`embed(List<String>)`），任意 provider 都不会把 metadata
  拼进 embedding（对齐原 DashScope metadata-mode=NONE）。
- **换模型 = 换向量空间**：删除 Weaviate 集合（`AgentKnowledgeChunk`）后重启（按新维度重建），
  再全量 reindex；pgvector 桌面端代码写死 1536，跨模型使用需同步改维度/重建表。
- 选型验证：`OllamaEmbeddingVectorStoreIntegrationTest` 用真实 Ollama + Weaviate 验证
  1024 维、近义改写查询命中、原生 Hybrid、BM25、兄弟扩展；质量对比建议用 golden set
  对 DashScope / bge-m3 / qwen3-embedding 各跑一次 Recall@k / MRR。
- 注意：rerank（gte-rerank-v2）与对话模型仍走 DashScope；DashScope 欠费时检索降级为按向量/融合序
  返回（rerank 不可用路径），不影响 Ollama 嵌入。

## 5. 部署

- compose 新增 `weaviate` 服务：`docker.m.daocloud.io/semitechnologies/weaviate:1.39.16`
  （服务器不可达 Docker Hub 的既定镜像源约定；client6 最低要求 1.32），
  `DEFAULT_VECTORIZER_MODULE=none`、匿名鉴权（不发布宿主机端口，仅 compose 网络）、
  named volume `weaviate-data`、healthcheck `/v1/.well-known/ready`（镜像内含 wget）。
- backend：`APP_VECTOR_STORE/APP_WEAVIATE_*` 环境变量；本地开发默认 `127.0.0.1:8080/50051`
  （本机 `aster-weaviate` 容器），**application-local.yml 无需改动**。
- 离线安装包：`package.sh` 拉取（daocloud retag）+ save `weaviate.tar`，
  `.env.example` 中 `WEAVIATE_IMAGE` 改指本地 tag。
- 发布脚本：compose 同步后幂等创建 weaviate 容器并容器内探活。
- 本地容器：`docker run -d --name aster-weaviate -p 127.0.0.1:8080:8080 -p 127.0.0.1:50051:50051 …`
  （数据卷 `aster-weaviate-data`）。
- Maven 解析：client6 从 Maven Central 拉取偶发卡顿，可 `-s` 临时 settings 走阿里云镜像
  （`https://maven.aliyun.com/repository/public`，mirrorOf=central）。

## 6. 数据回填与回滚

- 回填：`POST /ai/agent-knowledge/documents/reindex-all?agentKey=…`（admin）——按 scope
  列出全部文档 ID 逐个异步 `indexDocument`（删旧向量 → 重新解析/嵌入 → 写 Weaviate），
  各文档生命周期保持不变，失败由 `AgentKnowledgeIndexRecoveryJob` 重试；向量重新生成，
  无需导出旧向量。
- 回滚：`APP_VECTOR_STORE=pgvector` + 重启 backend。旧 `agent_knowledge_vector` 表与
  pg 路径代码完整保留（切换期间 pg 数据不再有新写入，回滚后检索的是切换时点快照）。

## 7. 已知取舍

1. `hybridSearch` 无逐对象相似度阈值语义（原生 hybrid 不支持 per-channel threshold），
   `maxVectorDistance` 以距离上限近似（similarity = 1 − distance）；pg 路径保持阈值预筛。
   效果以 golden set 评测为准。
2. 生命周期翻转由单条 UPDATE 变为逐对象 PATCH（文档 chunk 数内 ~N 次请求），
   发布事务内失败会回滚 DB 但 Weaviate 可能已部分翻转；影响面小（reindex/重新发布即愈），
   不做分布式事务。
3. 一次 recall 三次查询（向量/词法/混合），Weaviate gRPC 往返都在 10ms 级，3s 检索预算充足。
4. gse 对中英混排的 BM25 打分可能有偏差（社区已知议题），用评测数据在 gse/trigram 间定夺。

## 8. 验证口径

- 单测：既有 stub 式用例保持（端口扩展方法带默认空实现）；新增 FilterConverter/schema 用例。
- 冒烟：本地 aster-weaviate + 后端，上传 → verify-retrieval → 带知识检索聊天，核对 ragTrace。
- 质量回归：`evaluation/eval-sets/run_knowledge_retrieval_eval.py`（golden set）对比
  pgvector 基线（Recall@k / MRR / P95），按结果调 `bm25-tokenization` / `hybrid-alpha`。
