# 知识库模块全景图与修订计划 v2（2026-11-10）

> 定位：专攻知识库后的模块底稿。内容 = 现状全景（对代码核实）+ 外部评审逐条判定 + 修订六阶段计划。
> 阶段 0（过滤注入面收口）已完成：commit 683ff85，bug 记录 `docs/bugs/bug-2026-11-10-rag-filter-injection-hardening.txt`。
> 本文档取代 `docs/design/2026-11-08-knowledge-base-v2.md` 中已分叉的 §4/§5（新表模型与四段状态机未实施，见 §8）。

## 0. 结论先行

- 模块体量：后端 56 文件约 5250 行（`knowledge/document/` + `knowledge/retrieval/`），24 个测试类；前端 `KnowledgeAdmin.vue`（613 行）+ `CitationPreview.vue`。
- 最大缺口不是功能，是**测量**：离线无 golden set，在线无 P95/空召回看板。阶段 1 是一切优化的前提。
- 真实量级：生产库当前约 0 文档（单租户个人使用）；**设计目标量级 = 10 万切片、检索 P95 < 3s、词法通道在 10 万切片 scope 内 P95 < 1s**。下文所有量级注记以此为准绳。
- 三个计划外发现：① `references` 是孤儿数据；② 评测数据源现成（消息表 trace JSONB 已含完整 trace+人工判例）；③ README「意图分流」与代码不符（路由已退出聊天主链路）。

## 1. 全景图

```
┌─────────────────────── 入库（异步 knowledgeIndexExecutor, core2/max4/queue50）────────────┐
│ 上传(PDF/Office/图片) ──→ MinerU 远程SaaS ──→ 统一块(TEXT/TABLE/FIGURE/EQUATION)          │
│  幂等键+logicalKey版本号      申请→PUT→轮询→zip     blockId/page/bbox/caption               │
│        │                         │失败→Docling Serve→Tika/POI 双级降级(质量无标记!)       │
│        ▼                         ▼                                                       │
│  图纸分组 FigureGrouper ──→ 视觉增强 FigureEnrichmentService(≤30组/文档,失败重试1次)      │
│  几何合并≤4块/组              结构化描述+假设问题(失败降级仅图注; 同步阻塞索引线程)         │
│        ▼                                                                                 │
│  StructuredKnowledgeChunker：4类chunk(TEXT 1200字/表格行1行1块/figure_description/问题)   │
│        ▼                                                                                 │
│  DashScope embedding(1536维,批25,并行度默认1) ──→ agent_knowledge_vector(HNSW,COSINE)     │
│        ▼                                                                                 │
│  状态翻转：publish 事务内 旧ACTIVE→ARCHIVED / READY→ACTIVE（行锁原子,无回滚开关）          │
│  失败：FAILED+next_retry_at → Recovery Job 每60s重试,上限3次                              │
└──────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────── 检索（同步, 整链预算 timeout-ms=3000, 无分段）─────────────────────┐
│ 前端开关 knowledgeSearch(默认false!) ──→ retrieveWithContext                              │
│      ├── 向量召回: HNSW + filter(tenant/agent/ACTIVE), recall-top15, threshold0.4         │
│      │      ⚠ HNSW先取邻居后过滤, 过滤严格时可能取不满且静默                               │
│      ├── 词法召回: scope内逐行扫描, ILIKE '%2-gram%' ×≤20(CASE在SELECT, trgm GIN够不着)   │
│      │      ⚠ 无停用词/无长度归一/limit(20)截断后半句                                     │
│      ▼                                                                                   │
│   RRF融合(k=60) → 防御过滤(仅ACTIVE) → DashScope rerank(≤20篇,1200ms,败降向量序)           │
│      ⚠ rerank 内部静默 limit(20), 池15+15=30, 第21~30名无日志消失                          │
│      → 截断top3 → 兄弟扩展(向量式: 命中块文本再搜一次, 2节×4块, 无预算检查,排在末端)        │
│      ▼                                                                                   │
│   context拼装(不可信前缀) ──→ Workers.knowledgePrompt 拼进本轮用户消息(非system)           │
│      ▼                                                                                   │
│   流式回答 ──→ 文本内[文件名#切片号]标注 ──→ 前端引用卡(点击→切片预览)                     │
│   超时语义: 整链超时→空context+TIMEOUT标签, 已完成的重排结果一并作废                        │
│   trace三出口: agent_run.rag_trace_json / agent_chat_message.rag_retrieval_trace / SSE    │
└──────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────── 评测闭环（阶段1建成, 现状缺整个环）────────────────────────────────┐
│ 离线: golden set(30-50题四类+负例) → 一条命令runner → recall@k/MRR/通道贡献/P95 → 门禁     │
│ 在线: 消息表trace + Micrometer计数器(已有ragRecallEmpty/ragDegraded) → 看板(缺) → 告警     │
│ 人工判例: ChatRoom 每候选RELEVANT/PARTIAL/IRRELEVANT → 判例原地写入消息表trace(已有)       │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. 入库管线（现状）

### 2.1 入口与幂等
- 唯一上传入口 `POST /ai/agent-knowledge/documents`：同步 `upload()` + 异步 `indexDocument()`（`AgentKnowledgeDocumentService.java:230`）。幂等键（`Idempotency-Key` 头，1-64 位）命中即返回旧文档；同 `logicalKey` 自动版本号 +1 并记 supersedes；`autoPublish=false` 走人工审批。
- 重索引：`POST .../reindex`（admin）。ACTIVE/ARCHIVED 版本重索引失败保持原生命周期，不误下线。

### 2.2 解析与降级
- MinerU 为远程 SaaS（mineru.net，token 配置），四步：申请地址→PUT→轮询→下载 zip。产出统一块（TEXT/TABLE/FIGURE/EQUATION，带 bbox/图注/HTML 表格行列）。
- 降级链：MinerU 异常→Docling Serve（可配 fallback）→Tika/POI。0 切片视为失败。
- ⚠ 降级只保证"能入库"，质量断崖无标记（`parser` 键已入 metadata：`AgentKnowledgeDocumentService.java:402-405`，但无 degraded 语义）→ 阶段 3 修。

### 2.3 图纸增强
- 顺序：`indexDocument → mineruChunks → persistBlocks → figureSupport(FigureGrouper 几何分组 → FigureEnrichmentService.enrich)`（`AgentKnowledgeDocumentService.java:424-438`）。
- 预算 min(30, 组数)，每组一次视觉调用（多碎片=多 Media 单调用），强制 JSON、解析失败重试 1 次，仍失败降级仅图注；输入隔离：只传该图碎片字节+一行图注 hint。
- ⚠ 同步阻塞在索引线程，最多 60 次串行视觉调用，无并发限流。

### 2.4 分块
- 四类 chunk：TEXT（标题聚合 sectionPath+1200 字上限）、表格行（1 行/块，重复章节/表名/表头）、figure_description（摘要/要素/流向/图内文字/推断五段）、figure_question（≤4 条/图）。
- 命名与 v2 设计稿不同：代码用 `structuredContentType=table-record` 与 `chunkRole=figure_description/figure_question/figure_caption`。
- 父子分块无物理表：向量 metadata 带 `parentDocumentId/parentLogicalKey/previousChunkIndex` 链接字段（`:938-943`），检索侧兄弟扩展是轻量替代。

### 2.5 向量入库与索引
- 表 `agent_knowledge_vector`：PgVectorStore 自建（1536 维、COSINE、HNSW）；`KnowledgeVectorSchemaInitializer`（ApplicationRunner）幂等校正 metadata jsonb + 补 4 索引：scope 表达式索引 `(tenantId,agentKey,lifecycleStatus)`、documentId 表达式索引、**content trgm GIN**、metadata GIN（`KnowledgeVectorSchemaInitializer.java:31-72`）。
- chunkId 为确定性 UUID（`nameUUIDFromBytes(documentId|chunk|index)`）；重索引前按 documentId 删旧向量。
- embedding：DashScope，`metadata-mode: NONE` 只嵌正文，批 25，批间并行度默认 1。

### 2.6 状态机
- `status`（PENDING/INDEXING/READY/FAILED/REJECTED）+ `lifecycle_status`（INDEXING/READY/ACTIVE/ARCHIVED/FAILED/REJECTED）。解析/增强无独立阶段（v2 设计稿的四段状态机未实施）。
- chunk 生命周期只在 `flipChunkLifecycle` 翻转（JSONB 合并写）：publish 事务内旧 ACTIVE→ARCHIVED、目标 READY→ACTIVE（FOR UPDATE 行锁）；reject 把 READY→REJECTED。
- ⚠ 无"一键切回上一版"：publish 只接受 READY 目标，ARCHIVED 旧版无法直接切回 → 阶段 6。

### 2.7 n8n 审批
- 后端→n8n：上传开案事件 + 恢复 URL 通知（独立单线程 daemon，5s/15s/60s 重试）。n8n→后端：`resume-info` 登记恢复地址（只进库不回显）、`review-events` 只接受 TIMEOUT 审计。
- "解析/切片/向量化"三阶段只是 n8n 画布展示概念（workflow-status 返回 indexMetrics），后端无对应状态。审批权威在后端 REVIEWER（`agent_reviewer` 表 + `reviewAccessGuard`）。

### 2.8 异步与重试
- `knowledgeIndexExecutor`（AbortPolicy，queue 50 满即拒绝）。图纸增强/嵌入串行阻塞其内。
- `AgentKnowledgeIndexRecoveryJob`：60s 扫描 FAILED 且 `index_attempts<3` 的文档重置重试。

## 3. 检索与回答链路（现状）

### 3.1 触发
- 纯前端开关：`knowledgeSearch` **默认 false**、`webSearch` 默认 true（`ManusAgentController.java:267-270`，"完全服从用户开关"）。意图路由 2026-11-08 起退出聊天主链路（`IntentRouterConfig.java:21-28`，只服务评测平台）→ 与 README 分叉，见 §8。
- 检索调用点 4 处：`ManusAgentController.java:320`（聊天主链路）、`:602`（v2 runs 路径，⚠ 只取 `.context()` 丢 references/trace）、`InternalEvalRunService.java:166`（评测）、`InternalEvalController.java:85`（verify-retrieval 调试）。

### 3.2 召回与融合
- 向量：recall-top15、threshold 0.4，filter `tenantId/agentKey/lifecycleStatus=ACTIVE`（FilterExpressionBuilder，阶段 0 已收口）。
- 词法：`lexicalTerms`（`:341-352`）——英文/数字词(≥2字符)整词 + 中文 2-gram 滑窗，**LinkedHashSet 去重后 limit(20) 按插入顺序截断**（30 字问题后半句丢失）；无停用词；SQL 对 scope 内逐行 `CASE WHEN content ILIKE ?` 求和（CASE 在 SELECT，trgm GIN 无法下推）。
- 融合：RRF k=60（`reciprocalRankFusion`），词法分只影响词法排名→折算融合，不直接进最终分。

### 3.3 重排与截断
- DashScope rerank：`app.rag.rerank.enabled:true`、model `gte-rerank-v2`、timeout 1200ms；MAX_DOCUMENTS=20 **静默 limit**（`DashScopeRerankService.java:74`）、每篇 1200 字符；任何失败返回 empty→保持向量序。
- 池 = 向量15+词法15 RRF 去重，最多 30；第 21~30 名（按融合分）无日志消失 → 阶段 3 显式化。

### 3.4 兄弟扩展
- `expandSiblings`：命中 TEXT/TABLE 块后，**拿命中块文本当 query 再做一次向量检索**（topK=6、threshold 0）取同 documentId+sectionPath 块，≤2 节×4 块（`AgentKnowledgeRagService.java:259-275`）。
- 三重问题：多一次 embedding、章节>6 块漏取、返回为相似度序而非阅读序（跨行表格/条款编号乱序）→ 阶段 3 改元数据 SQL 查询。

### 3.5 上下文拼装与回答
- 检索侧安全前缀（`:122-124`：不可信资料/不执行指令/引用标注规范）+ prompt 侧 `Workers.knowledgePrompt`（"【本轮检索到的私有知识资料】…"），拼在**本轮用户消息前缀**（非 system message）。
- 带图提问：图片先 VLM 分析，`retrievalQuery` **替换**检索词（`ManusAgentController.java:294-318`）；分析失败不影响文本检索。

### 3.6 引用与 trace
- ⚠ `LoveKnowledgeReference`（references）为**孤儿数据**：组装后无消费方、不落库，前端对应分支是死代码。实际引用链路 = 回答文本 `[文件名#切片号]`（`ChatRoom.vue:273` citationPattern）+ `ragTrace.candidates` 匹配 chunkId 渲染引用卡，点击调切片预览接口。
- trace 三出口：`agent_run.rag_trace_json`（restart 重放用）、run 完成写 `agent_chat_message.rag_retrieval_trace`（前端历史回读+**人工判例原地更新于此**）、SSE `rag-trace` 事件实时推。
- **阶段 1 的离线 runner 可直接从消息表收历史 trace，采集零开发。**

### 3.7 兜底语义
- TIMEOUT/OVERLOADED/FAILED/NO_HIT 四态统一：空 context + 空 references + trace 状态标签（前端 ragStatusLabel：无命中/超时/繁忙/失败）。模型按自身知识作答，**无"库中无答案应说不知道"的检索侧信号** → 负例题进 golden set 验证。

### 3.8 端到端时序（消息→回答）
1. 前端发消息（SuperAgent.vue:945）→ SSE `GET /ai/manus/chat` → `doChatWithManus`（:232）
2. 落 USER 轮次（:261）→ 带图则 VLM 分析+检索词改写（:294-318）
3. `knowledgeSearch=true` 才检索（:320）→ `retrieveWithContext`
4. 落 run：knowledge_context + rag_trace_json（AgentRunService.start）
5. SSE `rag-trace` 事件（ManusGraphOrchestrator.java:359）
6. 执行：DirectNode.java:127 `taskPrompt = Workers.knowledgePrompt(knowledge) + input` → 流式回答
7. 完成：completeManusRun（:860）写 ASSISTANT 消息 + rag_retrieval_trace
8. 呈现：ChatRoom 引用卡 + trace 面板；点击引用→切片预览；判例→POST judgments
9. 历史重放：GET messages → mapMessage/parseRagTrace

## 4. 数据底座与配置

### 表（Flyway 已压平 V1 baseline）
`agent_knowledge_document` / `_category` / `_block`（含 image_object_key）/ `_review_log` / `agent_reviewer`；向量表 `agent_knowledge_vector` 不归 Flyway（PgVectorStore 自建+Initializer 校正）。

### 检索相关配置
| 键 | 默认 | 位置 |
|---|---|---|
| app.agent.rag.top-k / similarity-threshold | 3 / 0.55 | AgentKnowledgeRagService.java:45 |
| app.agent.rag.recall-top-k / recall-similarity-threshold | 15 / 0.4 | :51 |
| app.agent.rag.timeout-ms | 3000 | :57 |
| app.agent.rag.sibling-expansion-enabled / max-hits / extra | true / 2 / 4 | :61 |
| app.rag.rerank.enabled / model / timeout-ms | true / gte-rerank-v2 / 1200 | DashScopeRerankService.java:42 |
| app.knowledge.index.embedding-parallelism | 1 | AgentKnowledgeDocumentService.java:59 |
| app.knowledge.figure-enrichment-enabled / max-per-document | true / 30 | FigureEnrichmentService.java:49 |
| app.agent.rag.index-recovery-enabled / retry-interval-ms | true / 60000 | AgentKnowledgeIndexRecoveryJob.java:15 |

## 5. 外部评审逐条判定（2026-11-10）

| # | 议题 | 判定 | 去向 |
|---|---|---|---|
| 1 | 词法通道脆弱 | ✅成立。两处精确化：①"全表扫"→scope 内逐行扫描（scope 索引在，10 万切片结论不变）；②词法分只污染词法排名经 RRF 折算，不直接进最终分 | 阶段 2 |
| 2 | HNSW+过滤召回陷阱 | ⚠原理成立，缓解半已有：trace 已带 vectorCandidateCount/lexicalCandidateCount，缺看板告警。iterative_scan 需先查服务器 pgvector 版本 | 阶段 1（告警）+ 阶段 6（预研） |
| 3 | rerank 静默截断 | ✅机制确认（`DashScopeRerankService.java:74`）。定性修正：21-30 被丢是"池→精排"必然，真问题是截断在 rerank 内部无日志不可评审 | 阶段 3 |
| 4 | 兄弟扩展向量式负优化 | ✅完全同意 | 阶段 3 |
| 5 | 预算无分段/超时语义 | ✅成立。补充：超时语义有定义（空+标签），真缺口是兄弟扩展排末端无预算检查、超时连累已完成重排作废 | 阶段 3 |
| 6 | 文档级 ACL 缺失 | ✅成立，多租户/对外前置；category 是现成挂载点 | 阶段 6 backlog |
| 7 | 缺查询理解层 | ✅成立，知识库最值钱场景；轻量起步（单次改写+可选双查询） | 阶段 5 |
| 8 | 降级无质量标记 | ✅成立且便宜（parser 键已存在，派生 degraded 半天） | 阶段 3 捎带 |
| 9 | 归档切换无回滚 | ✅成立（publish 只接受 READY 目标） | 阶段 6 |
| 10 | 在线指标缺失 | ✅成立（计数器已有，缺百分位+看板） | 阶段 1 |
| — | MinerU 出网 | 同意；Docling 私有化已在降级链，对外叙事备好 | §8 叙事 |
| — | 图表达缺量级/降级语义/评测环 | 全部接受，本文档已补 | 本文档 |

## 6. 修订计划 v2

依赖：0 ✅ → **1** →（**2** ∥ **3**）→ **4** → **5** → **6**。每阶段完成落 `docs/context/session-*.md`；检索改动一律用阶段 1 的同一套题对比。

### 阶段 1：golden set 基线 + 在线看板（评测闭环两端一次成型）
- 语料先行：选 2-3 份真实 PDF（必含宽表格+无图注图纸）入库，题目从语料长出。
- golden set 30-50 题，四类（文本事实/表格行/图纸含无图注/精确术语）+ ≥5 道负例题（库中无答案，验证"该说不知道"）。
- 离线 runner（沿用 `evaluation/eval-sets/run_baseline.py` 惯例，数据源可直接读消息表历史 trace）：块级 recall@k（宽判：命中块或其 section 父级）、MRR、各通道命中贡献、上下文含答案率、期望块存在性（入库/检索失败归因）、P95。**一条命令复跑全量基线**，结果落 `evaluation/eval-sets/baseline-*.json`。
- 在线指标：Micrometer timer（vector/lexical/rerank 百分位）+ 空召回率、rerank 降级率、vectorCandidateCount 过低告警（先可做成 `/internal/eval` 聚合端点）。
- 完成标准：四类题各有基线数字；一条命令复跑；在线看板出数。

### 阶段 2：词法通道重做
- 零成本先行：ILIKE 挪进内层 WHERE（trgm GIN 已存在，≥3 字符词项立刻受益）。
- 决策点：**是否动生产 Postgres 镜像**。pg_bigm（对齐现有 2-gram，需自定义镜像）vs Java 分词写 tsvector('simple')+GIN（零镜像变更，顺带解决停用词/词切分）vs zhparser（词级语义，需镜像+分词语义变化）。
- 无论哪条：加停用词；修长度归一（按命中词数/切片长度或直接交给 RRF）。
- 完成标准：精确术语类召回提升、其他类不降、P95 不升（同一套题）。

### 阶段 3：检索骨架修缮（一个 PR，全在 recall() 链路）
- rerank 进池显式化：融合分取 top20 + 日志（池>20 时记录被砍名额）。
- 兄弟扩展 SQL 化：JdbcTemplate 按 documentId+sectionPath ORDER BY chunkIndex；补 `(documentId, sectionPath)` 表达式索引；沿用 SiblingExpansionTest + 补"同节>6 块"用例。
- 预算分段：兄弟扩展前查剩余预算（默认超 60% 跳过）；整链超时降级返回已完成 selected 而非空。
- parseQuality 标记：parser≠mineru → metadata `parseQuality=degraded`，回答侧条件提示。
- 完成标准：长节不丢块、上下文阅读序正确、golden set 不降、P95 不升。

### 阶段 4：引用回图（前置裁决）
- 前置：**裁决引用权威表示**——建议以 `trace.candidates + blockId` 为权威，`references` 退役；否则把图挂在无人消费的结构上。
- figure 命中带 `figureImages`（metadata 已有）→ 前端展示原图；需新增按 blockId/objectKey 取原图端点（MinIO 预签名，走现有存储）。
- 回答侧 VLM 读原图：单独评估，不默认开。
- 完成标准：图纸类题目答案可点开原图。

### 阶段 5：查询理解 + contextual retrieval（两个离线实验，数据说了算）
- 查询理解：单次 LLM 改写 + 可选双查询（先例：图片 retrievalQuery 改写）；多跳拆解仅当双查询证明不够再做。
- contextual retrieval：块级语义前缀写副本索引，离线对比 + 每百块成本记录。
- 完成标准：golden set 增益明确才进正式管线，否则记录结论归档。

### 阶段 6：按数据与规模决定（不预先承诺）
视觉向量轨（multimodal-embedding/ColQwen）｜PDFBox 回源重裁（figureTruncated 比例触发）｜pgvector iterative_scan 预研（先查版本）｜版本回滚开关｜文档级 ACL（category 挂载）｜MinerU 私有化/出网叙事。

## 7. 待用户拍板

1. **README 意图分流**：恢复路由进聊天主链路，还是改 README 承认"开关制"？（代码现状是开关制，knowledgeSearch 默认 false）
2. **阶段 2 镜像决策**：能否为词法通道动生产 Postgres 镜像（pg_bigm/zhparser）？
3. **阶段 4 引用权威**：接受"trace.candidates+blockId 为权威、references 退役"？
4. **语料**：手头是否有合适的真实 PDF（含宽表格+无图注图纸）？

## 8. 文档与代码的分叉记录

- `2026-11-08-knowledge-base-v2.md` §4（kb_* 新表）与 §5 部分内容**未实施**，P1 走了 agent_knowledge_* 加 chunkRole 的务实路线，以该文档 §9 与本文档为准。
- README「意图分流」：代码中路由已退出聊天主链路（`IntentRouterConfig.java:21-28`），待 §7.1 拍板后修正一方。
- `LoveKnowledgeReference`：后端组装后无消费方，前端 ChatRoom.vue reference-card 分支为死代码，待阶段 4 裁决后清理或复活。
- v2 runs 路径（`ManusAgentController.java:602`）只取 context 丢 references/trace，双入口不一致，阶段 3 顺手收口。
