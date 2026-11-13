# 会话摘要：知识向量存储 pgvector → Weaviate 迁移（2026-11-13）

> 取代 session-2026-11-12-parse-service.md 中知识检索部分的状态描述（解析服务内容不受影响）。

## 需求

按《Weaviate 优势说明》与《pgvector 迁移 Weaviate 实施方案》，把向量检索层整体换成 Weaviate，
**充分利用原生 Hybrid Search**（用户中途明确要求）；不考虑迁移成本，不走双写/灰度。
计划经用户批准（含桌面端默认决策：加 provider 开关、桌面保持 pgvector）。

## 架构与改动

新增端口 `com.zwx.zwxagent.knowledge.vector.KnowledgeVectorStore`（extends Spring AI VectorStore，
bean 名保持 `agentKnowledgeVectorStore`），双实现按 `app.vector-store.provider` 装配
（`AgentKnowledgeVectorStoreConfig`）：

- **WeaviateKnowledgeVectorStore**（服务端/本地默认）：`io.weaviate:client6` 6.3.2。
  - similaritySearch：nearVector + where 过滤（score = 1 − cosine distance，与 pgvector 语义一致）；
  - **hybridSearch：原生 Hybrid Search（FusionType.RANKED = 服务端 RRF k=60，alpha 可配）**，
    替代迁移前 Java 侧 RRF；`ReciprocalRankFusion` 工具类供 pg 路径同公式融合；
  - bm25Search：原生 BM25（text 属性 tokenization=gse 中文分词）；
  - fetchSiblings/listByDocument：纯取回 + SortBy chunkIndex；findById：fetchObjectById + scope 校验；
  - updateLifecycleStatus：where 取 ID + 逐对象 PATCH；deleteByDocumentId：deleteMany(where)；
  - 惰性建连（启动不受 Weaviate 可用性影响）+ 查询向量 LRU 缓存（hybrid 与 nearVector 复用）；
  - 元数据：白名单 typed 属性（38 个）+ extraMeta JSON 收纳其余键，读取侧展开还原。
- **PgKnowledgeVectorStore**（桌面端/CI/回滚）：包装原 PgVectorStore，原直查 SQL 逐字搬入，
  行为零变化（application-desktop.yml 设 provider=pgvector；CI 设 APP_VECTOR_STORE=pgvector）。

业务层改造：`AgentKnowledgeRagService`（lexicalRecall/expandSiblings SQL → 端口 bm25Search/fetchSiblings，
融合池 → hybridSearch，新增 metrics.ragStageMillis("fusion")；四通道 trace 结构不变）；
`AgentKnowledgeDocumentService`（删旧向量/生命周期翻转/切片清单/引用预览 → 端口）；
`KnowledgeAdminController` 新增 `POST /ai/agent-knowledge/documents/reindex-all`（全量回填入口）。

其他：WeaviateSchemaManager/WeaviateSchemaInitializer（幂等建集合，失败只 warn）、
WeaviateHealthIndicator（provider=weaviate 时注册）、WeaviateFilterConverter（Filter.Expression →
原生 where，fail-closed）、application.yml 增加 app.vector-store/app.weaviate 配置块。

## 部署面

- compose：新增 `weaviate` 服务（`docker.m.daocloud.io/semitechnologies/weaviate:1.39.16`，
  **ENABLE_TOKENIZER_GSE=true**，无 vectorizer 模块，仅 compose 网络可达，volume weaviate-data，
  healthcheck ready 端点）；backend 增加 APP_VECTOR_STORE/APP_WEAVIATE_* 环境变量。
- package.sh：离线包内置 weaviate 镜像 tar（daocloud 拉取 retag）+ .env.example WEAVIATE_IMAGE 改本地 tag。
- deploy-to-server.sh（本地脚本）：compose 同步后幂等 `up -d weaviate` + 容器内探活。
- application-local.yml **零改动**（默认 127.0.0.1:8080/50051 指向本地 aster-weaviate 容器）。
- 本地容器：`docker run -d --name aster-weaviate -p 127.0.0.1:8080:8080 -p 127.0.0.1:50051:50051
  -e ENABLE_TOKENIZER_GSE=true … -v aster-weaviate-data:/var/lib/weaviate`（镜像源 daocloud）。

## 验证

- **真实 Weaviate 集成测试** `WeaviateKnowledgeVectorStoreIntegrationTest`（stub embedding 字符哈希向量，
  免 DashScope）：写入（v3 确定性 UUID + extraMeta 往返）→ nearVector 过滤检索 → 原生 hybrid（RANKED）
  → BM25 gse 中文命中「沪昆高速」→ fetchSiblings chunkIndex 阅读序 → findById（租户隔离拒绝）
  → 生命周期 PATCH → deleteByDocumentId，全绿。
- 单测：FilterConverter 8/8、SiblingExpansion 3/3、VersionFilter/FilterSafety/RerankPool/
  LexicalTerms、DocumentService 全套（Delete/VectorBatch/Upload/Idempotency/ReviewEvent）全绿。
- 本地后端（launchctl 重启新 jar）：/api/health ok；actuator 整体 UP、**weaviate 组件 UP**；
  启动即建集合 `AgentKnowledgeChunk`（38 属性，text=gse，311ms），日志确认惰性连接与 alpha=0.5。

## 已确认/修复的真实 bug（docs/bugs/ 有记录）

1. **ENABLE_TOKENIZER_GSE**：Weaviate 1.32+ gse 分词器默认关闭，建集合 422；容器必须显式开启
   （compose + 本地容器已加）。
2. **hybrid + maxVectorDistance 结果清空**：1.39 上 ranked fusion + 距离上限组合实测恒返回空
   （curl 分层探测复现）；hybridSearch 不再设置该参数，阈值仅 pg 路径生效（javadoc 注明不对称）。
3. **Weaviate 属性 null 值**：client6 对未设属性可能返回 null，Spring AI Document 拒绝 null
   metadata；toMetadata 跳过 null（键缺失语义）。

## 服务器部署与自测（0.7.40，2026-11-13 完成）

- 发布：`deploy-to-server.sh --force` → backend/frontend 0.7.40 + compose 同步 + weaviate 容器创建；
  脚本 force 分支已补 `NEED_COMPOSE_SYNC=true`（compose 未提交时 force 也要同步）。
  注意变更检测基于 **git 已提交范围**，工作区改动不会被 auto 模式发现。
- 容器：backend 0.7.40 healthy / weaviate healthy（不发布宿主端口，仅 compose 网络）/
  postgres healthy；actuator overall UP + `weaviate: UP`；公网 `https://www.zhuwenxuan.online/api/health` ok。
- 服务器集合自动创建：`AgentKnowledgeChunk`（38 属性，text=gse）。
- 数据面探针（服务器容器内，生产形态）：hybrid(query+显式vector+where+rankedFusion) 2 hits
  （RRF score 0.0083）、nearVector+where 2 hits、v3 确定性 UUID 插入、chunkIndex 排序、探针清理。
  教训：GraphQL Get 查询的闭合括号（ServerProbe 本身是大括号）手数极易错，探针脚本要程序化校验
  括号配平；hybrid 只传文本不传 vector 会报 `VectorFromInput was called without vectorizer`
  （生产 Java 实现恒带显式 vector，无此问题）；auto-schema 的 word 分词 BM25 中文整串一词不命中
  ——这正是真实集合必须显式 gse 建模的原因。
- 降级路径：DashScope 欠费下 `/internal/eval/retrieval` 返回 HTTP 200 + `status: TIMEOUT`
  （3s 检索预算兜底），trace 完整、无 500。
- 旧表完好：`agent_knowledge_vector` 21 条向量原样保留（回滚底座）；生产库当前无 ACTIVE 文档。

## 增补：Ollama 免费嵌入部署（2026-11-13，0.7.41）

- 需求：DashScope 欠费阻塞向量化，用户选定 Ollama + `qwen3-embedding:0.6b`（1024 维，免费、数据不出服务器）。
- 实现：
  - `app.embedding.provider=dashscope|ollama`（默认 dashscope，兼容桌面/回滚）；
  - **不注册自建 EmbeddingModel**（DashScope 与 Ollama 自动配置 bean 均 @Primary，自建会多 primary 歧义，
    见 bug-2026-11-13-multiple-primary-embedding-beans.txt），改为
    `AgentKnowledgeVectorStoreConfig` 按 provider 显式取 bean 名（dashscopeEmbeddingModel / ollamaEmbeddingModel）；
  - Ollama 模型名走 `spring.ai.ollama.embedding.model`（env `APP_OLLAMA_EMBEDDING_MODEL`），
    连接走 `spring.ai.ollama.base-url`（env `APP_OLLAMA_BASE_URL`）；
  - 知识库 add() 改为只嵌入纯正文（`embed(List<String>)`），任意 provider 都不会把 metadata 拼进 embedding。
- 部署：compose 新增 `ollama` 服务（`ollama-data` 卷、expose 11434、mem_limit 2g、
  `ollama list` 健康检查、`OLLAMA_KEEP_ALIVE=24h`），backend 默认 `APP_EMBEDDING_PROVIDER=ollama`；
  deploy 脚本 compose 同步后幂等创建 ollama + 预热模型（已存在秒过）；离线包内置 ollama 镜像。
- 自测：
  - 本地/服务器均确认 `知识向量嵌入模型：provider=ollamaEmbeddingModel, bean=OllamaEmbeddingModel`；
  - `OllamaEmbeddingVectorStoreIntegrationTest`（真实 Ollama + Weaviate）：1024 维、近义改写查询命中、
    原生 Hybrid、BM25、兄弟扩展、按文档删除，全绿；
  - 本地与服务器端到端（真实 Ollama 向量写入真实集合 → `/internal/eval/retrieval`）：
    `status=SUCCESS`，vectorCandidates=1/lexicalCandidates=1，语义查询「车辆追尾事故怎么处理」
    命中「交通事故应急处置」（vectorScore≈0.576），context 含原文；测试对象已清理。
  - 服务器 0.7.41：backend/ollama/weaviate 均 healthy，actuator weaviate UP，公网 OK；
    集合按 1024 维场景重建（空集合，无数据迁移成本）。
- 本地环境注意：本机 DNS 把 Ollama registry 的 R2 重定向解析到非公网地址（28.0.0.42），
  `ollama pull` 会报 `redirect target not allowed`；改用 hf-mirror 下载
  `Qwen3-Embedding-0.6B-Q8_0.gguf` + `ollama create qwen3-embedding:0.6b -f Modelfile`。
  服务器可直连 registry，正常 `ollama pull`。
- 本地 launchctl backend job 现带 `APP_EMBEDDING_PROVIDER=ollama`（与 AGENTS.md 的启动命令差异：
  仅多了 embedding 环境变量；重启命令不变）。

## 本地运行时收敛（2026-11-13 晚）：模型/向量库只留服务器

- 需求：Ollama/Weaviate 全部部署在云服务器即可，本地删容器/镜像（本地磁盘紧张）。
- 服务器 compose：weaviate 增 loopback 端口 `127.0.0.1:18080:8080`、`127.0.0.1:50061:50051`；
  ollama 增 `127.0.0.1:11434:11434`（仅回环，模式与 postgres 一致；已同步并重建容器）。
- 本地隧道 `com.aster.ssh-tunnel`：新增 `-L 18080 / 50061 / 11434` 三条转发（plist 已更新并重新加载）。
- 本地已删除：`aster-weaviate`/`aster-ollama` 容器、两个数据卷、两个镜像、GGUF 文件、
  docker build cache（6.3GB）；保留 `aster-postgres` 与 latest 镜像（0.7.41 backend/frontend）。
  另有 9 个未使用的卷属于其他项目（flowable/n8n/mysql/redis 等），未动。
- 本地后端启动环境变量（已写入 AGENTS.md）：`APP_EMBEDDING_PROVIDER=ollama`、
  `APP_OLLAMA_BASE_URL=http://127.0.0.1:11434`、`APP_WEAVIATE_HTTP_PORT=18080`、
  `APP_WEAVIATE_GRPC_PORT=50061`；launchctl job 已按此重启。
- 自测：本地 backend health 200、actuator weaviate UP、日志确认走 18080/50061 + OllamaEmbeddingModel；
  隧道内 embed 1024 维（~90ms）；本地检索调用第二次 395ms（首次 3s 超时系冷启动/rerank 降级路径）。
- 注意：删本地 ollama 后，`ollama pull` 的 R2 DNS 问题只影响"本机再拉模型"，
  现在模型只在服务器（服务器可正常 pull）。本地无任何模型/向量数据副本。

## 线上知识库数据清空（2026-11-13 11:15，配合用户重新走上传流程）

- 清理前盘点：documents=0 / blocks=0 / categories=1（默认分类）/ reviewers=0 / review_logs=0；
  旧 pgvector 表孤儿向量 21 条；Weaviate 集合 0 对象；MinIO `knowledge/` 前缀 193 个对象（34MB）。
- 备份（先备份后删除）：
  - DB：`/opt/aster/backups/backup-2026-11-13-1113.sql`（1.1MB，含 21 条旧向量）；
  - 对象存储：`/opt/aster/backups/knowledge-storage-2026-11-13-1114.tar.gz`（18.9MB，193 个对象原目录结构）。
- 执行：`TRUNCATE agent_knowledge_vector` + `DELETE FROM agent_knowledge_category`；
  MinIO `knowledge/` 193 个对象逐个 DELETE（批量 DeleteObjects 的 SigV4/XML 组装 400，改为逐对象删除）；
  Weaviate 无对象无需处理。
- 验证：documents/blocks/categories/vectors 全 0，Weaviate count=0，MinIO `knowledge/` 0，backend health 200。
- 说明：默认分类会在管理员下次打开知识库/上传时由 ensureDefaultCategory 自动重建；
  `agent_run.knowledge_context`/`rag_trace_json` 属历史运行记录，未删除。

## 《新能源发电技术》入库实战（2026-11-13，0.7.41）

- 文件 90MB / 254 页；最终方案：拆成 3 份（85/85/84 页）走 **MinerU 云端** 逐份导入。
- 结果：3 篇文档 READY+ACTIVE，切片 543 + 543 + 534 = **1620**；Weaviate 1620 对象（全 ACTIVE）；
  实测检索「光伏发电的基本原理」→ SUCCESS，vector 15 + lexical 15 → 30 候选，441ms，
  命中「太阳能发电 > 4.4 太阳能光伏发电技术」，上下文正确。
- 过程中发现/修复/记录：
  1. **MinerU 单文件 200 页上限**（254 页被拒）→ 拆分解决；
  2. parse-service 默认 **50MB 上传上限**（413）→ 服务器 .env `PARSE_MAX_BYTES=157286400`；
  3. **backend JVM 堆默认只有 25%**（90MB PDF OOM）→ compose mem_limit 2560m +
     `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`（bug-2026-11-13-backend-jvm-heap-oom-large-pdf.txt）；
  4. **Docling（parse-service）在 3G 内存上限下 OOM**：254 页峰值 3.12G；拆成 85 页/份仍 OOM
     （本书图片多）。`dmesg` 确认 memory cgroup OOM，容器重启。若要本地方案需 ≥5-6G 内存或 GPU；
     当前服务器与 Ollama/Weaviate 抢内存，不可行；
  5. 异步索引遇 Error（OOM）不会把文档置 FAILED，会永久卡 INDEXING（catch(Exception) 只捕 Exception，
     未改代码，列为改进项）。
- 当前文档解析配置（服务器 .env）：`APP_DOCUMENT_PARSER_PROVIDER=mineru`、
  `PYTHON_ENABLED=true`（Docling 作为回退）、`PYTHON_TIMEOUT_SECONDS=3600`、`PARSE_MAX_BYTES=150MB`。
- 运维注意：Docling 服务崩溃后可能残留已占用信号量/半开连接，重启 parse-service 可恢复；
  backend 重启后 JVM DNS 缓存会失效（曾因 parse-service 重建后 IP 变化导致请求悬挂，
  重启 backend 解决）；大文件建议拆分后导入。

## 遗留 / 受阻

- **DashScope 账户欠费（Arrearage）**：真实 embedding/rerank 全部 403，全链路 RAG 冒烟
  （上传 → verify-retrieval → 聊天 trace）与 golden set 评测
  （`run_knowledge_retrieval_eval.py` 对比 baseline-*.json，调 gse/trigram 与 hybrid-alpha）
  在充值后执行；这是既有环境问题（上一轮基线同因失败）。
- **服务器向量回填**：embedding 已切 Ollama，**回填不再依赖 DashScope**；但生产库当前
  0 个 ACTIVE 文档，无内容可回填。重新有 ACTIVE 文档后：管理员
  `POST /api/ai/agent-knowledge/documents/reindex-all?agentKey=<key>`。
- **rerank / 对话仍走 DashScope（欠费）**：检索在 rerank 不可用时按融合序降级（已有路径），
  不影响 Ollama 嵌入/检索；聊天与精排恢复需充值或另换 rerank 模型。
- **质量评测**：Ollama 方案的功能链路已验证，但 bge-m3 / qwen3-embedding 与 DashScope 的
  Recall@k / MRR 对比仍建议用 golden set 在充值/有语料后跑（需要 ACTIVE 文档集）。
- 服务器部署脚本本次为 force 发布（版本 0.7.40）；本地工作区改动尚未提交，提交时注意带上
  本会话全部改动（Java + compose + 文档 + bug 记录）。
- 全量 `mvn test`（663 例，~20 分钟）复跑：仅剩既有基线失败——创作模块 10 例
  （creative.asset 1 + creative.job 2 + creative.service 7，本次未触碰 creative 包）+
  DashScope 欠费 1 例（PgVectorVectorStoreConfigTest，schema ensure 已成功、走到真实 embedding
  被 Arrearage 挡住）；**知识/检索相关用例零涟漪**，WeaviateKnowledgeVectorStoreIntegrationTest
  在全量套件中同样通过。
- 服务器未部署：需要时运行 `./deploy/scripts/deploy-to-server.sh`（会检测 compose 变更并创建
  weaviate 容器、首次从 daocloud 拉镜像），然后以管理员调用 reindex-all 回填向量。
- 本地 `agent_knowledge_vector`（pgvector 表）与服务器数据未动——回滚随时可用：
  `APP_VECTOR_STORE=pgvector` + 重启。
- 本地 /api/internal/** fail-closed（token 默认空），如需本地评测先给 launchctl job 注入
  PLATFORM_INTERNAL_TOKEN。

## 环境注意事项

- 本地 weaviate 容器名 `aster-weaviate`（volume `aster-weaviate-data`）；重建容器务必带
  `-e ENABLE_TOKENIZER_GSE=true`。
- client6 从 Maven Central 首次下载可能卡死：本地仓库
  `/Users/zhuwenxuan/Desktop/maventool/maven-repository` 已手动装好 client6-6.3.2（jar+sha1）；
  若 CI/他机下载超时可用临时 settings 走阿里云 mirror（mirrorOf=central）。
- Weaviate client6 的 `CollectionHandle.data/query` 是**公共字段**（不是方法）；
  gse/trigram 分词器枚举在 `io.weaviate.client6.v1.api.collections.Tokenization`。
