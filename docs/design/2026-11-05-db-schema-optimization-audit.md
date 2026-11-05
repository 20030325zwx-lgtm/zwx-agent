# 数据表结构与查询效率审计（2026-11-05）

状态：**已全部实施并上线**（0.7.14 + PostgreSQL 参数重启，2026-11-05）。实施结果见文末第 7 节。
审计结论：**索引设计整体已经比较到位**，问题集中在
「宽 JSONB 列被列表/轮询无差别拉取」「知识库向量表用 `json` 类型导致过滤无法走索引」「模糊搜索缺 trigram」
以及实例参数仍是默认值。以下按收益排序，附证据与改法。

## 0. 现状快照（生产库）

- 表都很小：最大 `agent_knowledge_vector` 360 kB / 18 行，`agent_run` 328 kB / 6 行，`workflow_run` 272 kB / 29 行。
- 体积主要由 TOAST（JSONB 大字段）占据：`agent_run` 288 kB/328 kB、`workflow_run` 200 kB/272 kB、
  `media_job` 184 kB/248 kB、`creative_canvas` 104 kB/128 kB。
- 部分表 `seq_scan` 次数很高（media_job 71 万、workflow_node_execution 42 万、agent_instance 11 万），
  但**这不是问题**：行数只有 27/10/80，seq scan 是最优选择；且认领类查询的关键部分索引已存在且谓词精确匹配：
  - `idx_media_job_next_action(next_action_at) WHERE status IN ('QUEUED','SUBMITTING','RECONCILING','RUNNING','CANCEL_REQUESTED','FETCHING','INGEST_FAILED')`
  - `idx_workflow_node_execution_waiting(status, updated_at) WHERE status='WAITING_EXTERNAL'`
- 缺失扩展：没有 `pg_trgm`（模糊搜索）、没有 `pg_stat_statements`（无法做基于真实耗时的定位）。

## 1. P0：会成为瓶颈的三处

### P0-1 列表查询用 `SELECT *`，把 TOAST 大字段一起拉出来

证据：`WorkflowService.list()`（`WorkflowService.java:1880-1887`）`SELECT * FROM workflow_run ... LIMIT 50`，
行里含 `steps`/`artifacts` JSONB；列表页只用 id/workflow_key/status/current_step/created_at 等。
同理 `MediaJobRepository.SELECT`（`MediaJobRepository.java:39-45`）包含 `input_snapshot`/`model_snapshot`，
而调度器每次 tick 都 `SELECT` 全列 + `FOR UPDATE SKIP LOCKED` 认领一批。

影响：每次列表/轮询都要解压 TOAST 并反序列化，随运行步骤数线性放大；这是当前最容易被感知的「页面越用越慢」。
改法：
- 列表查询只选必要列（`WorkflowService.list`、`media_job` 认领改为返回 id + 必需字段，快照在需要时再取）。
- 需完整 JSON 的详情接口保持 `SELECT *`。
- 预期：列表页 DB 时间与内存占用下降一个数量级（现在已 200 KB/行 TOAST）。

### P0-2 `agent_knowledge_vector.metadata` 是 `json` 而不是 `jsonb`

证据：`\d agent_knowledge_vector` → `metadata | json`；全库其它 JSONB 列都是 `jsonb`。
检索/清理全走 JSON 取值：
- `AgentKnowledgeRagService.searchRequest()`（`:377-382`）过滤 `tenantId/agentKey/lifecycleStatus`；
- `lexicalRecall()`（`:222-247`）`WHERE metadata->>'tenantId'=? AND metadata->>'agentKey'=? AND metadata->>'lifecycleStatus'='ACTIVE'`，
  并对每个词做 `content ILIKE '%词%'`；
- `AgentKnowledgeDocumentService` 删除/详情：`WHERE metadata->>'documentId' = ?`（`:198-199`、`:686-690`）。

影响：`json` 没有 GIN/表达式索引能力 → 每次知识检索都要全表扫 + 逐行解析 JSON；词法通道还要全表 ILIKE。
这是知识库「内容一多就慢」的头号原因。
改法（两条可组合）：
1. `ALTER TABLE agent_knowledge_vector ALTER COLUMN metadata TYPE jsonb USING metadata::jsonb;`
   再建 `CREATE INDEX ... USING gin (metadata jsonb_path_ops);`（或按需建表达式索引
   `((metadata->>'documentId'))`、`((metadata->>'tenantId'), (metadata->>'agentKey'))`）。
2. 更彻底：把 `tenant_id / agent_key / document_id / lifecycle_status / chunk_index` 落成真实列
   （可由 metadata 生成列或写入端补列），给过滤 + 排序建复合索引；pgvector 0.8 可开
   `hnsw.iterative_scan = relaxed_order` 改善「带过滤的 ANN」召回。
- 注意：该表由 Spring AI `PgVectorStore` 维护，改类型需确认其 SQL 不依赖 `json`；建议先做 1，验证后再考虑 2。

### P0-3 `agent_run` 认领查询无法使用队列索引

证据：`AgentRunStateService.java:130-134`
`WHERE phase='QUEUED' AND engine_version=2 AND control_state='NONE' ORDER BY COALESCE(next_dispatch_at, created_at), id`，
而索引是 `idx_agent_run_queue(next_dispatch_at, id) WHERE phase='QUEUED'`：
- `ORDER BY` 用了 `COALESCE(...)` 表达式 → 无法用索引有序性（要全扫 + 排序）；
- 索引谓词缺少 `engine_version` / `control_state`。

改法：入队时就把 `next_dispatch_at` 写成非空（无则=created_at），SQL 改成
`ORDER BY next_dispatch_at, id`，索引改为
`CREATE INDEX idx_agent_run_queue ON agent_run (engine_version, control_state, next_dispatch_at, id) WHERE phase='QUEUED';`
预期：排队深度上来后认领从 O(n log n) 变 O(log n + k)。

## 2. P1：低成本索引与结构微调

| # | 问题 | 证据 | 建议 |
| --- | --- | --- | --- |
| P1-1 | 素材/项目模糊搜索走 `ILIKE '%q%'`，无 trigram | `CreativeAssetRepository.java:102`、`CreativeProjectRepository.java:123` | `CREATE EXTENSION pg_trgm;` + `gin (display_name gin_trgm_ops)`、`gin (name gin_trgm_ops)`（或统一到 `lower(...)` 表达式索引） |
| P1-2 | 素材列表 `status <> 'DELETED'` 不在索引里，翻页需回表过滤 | `CreativeAssetRepository.listForOwner`；索引 `idx_creative_asset_owner(tenant_id,user_id,created_at,id)` | 加部分索引 `(tenant_id,user_id,created_at DESC,id DESC) WHERE status <> 'DELETED'`；高频组合（media_type/starred）再加 `business_type` 类似的复合 |
| P1-3 | 冗余索引（写放大，无查询收益） | `uq_creative_project_identity(id,tenant_id,user_id)`、`uq_agent_run_owner_composite(id,...)` 以主键列开头；`idx_video_shot_project` 与 `idx_video_shot_scene` 前缀重叠 | 确认无 FK 依赖后删除 1~2 个 |
| P1-4 | 缺索引的外键（删除/级联时全表扫） | `agent_run.user_message_id/assistant_message_id`、`creative_asset_link.target_asset_id`、`video_*` 的 `(project_id,tenant_id,user_id)` | 按需补单列/复合索引 |
| P1-5 | 时间类型不一致 | `agent_tool_confirmation.created_at/decided_at` 是 `timestamp without time zone`，其余全库 `timestamptz` | 统一为 `timestamptz`（当前 DB 时区 UTC，暂无错值，属潜在坑） |
| P1-6 | 索引统计与自动 analyze | 各表 `last_analyze` 均为 never（只有 autoanalyze），高频变更小表 `n_mod_since_analyze` 24~59 | 对 `agent_instance/media_job/creative_project` 单独设 `autovacuum_analyze_scale_factor=0.02` |

## 3. P2：实例参数（零代码，收益明显）

当前是 initdb 默认值：`shared_buffers=128MB`、`work_mem=4MB`、`maintenance_work_mem=64MB`、
`random_page_cost=4`、`effective_io_concurrency=1`、`max_connections=100`。

8 核/8G、云盘（SSD）建议：

```
shared_buffers = 1GB
work_mem = 16MB
maintenance_work_mem = 256MB
random_page_cost = 1.1
effective_io_concurrency = 200
effective_cache_size = 4GB   (已是)
hnsw.ef_search = 100         (默认 40，知识检索召回可调)
```

并启用 `pg_stat_statements`（`shared_preload_libraries`）用于后续按真实耗时排序优化项——目前没有它，
本次结论只能靠代码与统计视图推断。

## 4. P3：规模再上来才做

- `agent_execution_event`、`media_job_event`、`agent_llm_call` 属事件流，量级增长后按月分区或加 TTL 归档。
- `workflow_run.steps/artifacts`、`creative_canvas.graph` 拆到子表（步骤行化），列表/详情各取所需。
- 仓储层统一规范「列表不 `SELECT *`」，避免新代码重复 P0-1。
- 若并发继续增长，再加 PgBouncer（当前 100 连接、单实例够用）。

## 5. 建议实施顺序

1. P0-1（只改 SQL，无迁移，风险最低，立刻可感知）
2. P1-1/P1-2（新增扩展与索引，纯 expand）
3. P0-3（入队写 `next_dispatch_at` + 换索引，需小改代码与迁移）
4. P0-2（json→jsonb + GIN，先在本地库演练；Spring AI 写入路径需回归）
5. P2 参数（重启 PostgreSQL，选低峰）

## 6. 说明

- 本次仅审计，未改动任何表、索引或配置。
- 所有「行数很少但 seq_scan 很高」的表不必处理，planner 选 seq scan 是正确的；
  上面的建议只针对「过滤/排序无索引可用」或「无谓拉取大字段」这两类真实放大项。


## 7. 实施记录（2026-11-05，0.7.14）

| 项 | 落地内容 | 验证 |
| --- | --- | --- |
| P0-1 | `WorkflowService.list` 改窄列（去掉 input/graph_snapshot）；`AgentRunService.findScopedRuns/findRecentRuns` 用 `SLIM_COLUMNS`（排除 activities/sse_events，单行约省 11KB） | 线上 workflow 列表 160ms、runs/recent 75ms |
| P1-1 | `V42`：`pg_trgm` + `idx_creative_asset_name_trgm`、`idx_creative_project_name_trgm`、`idx_creative_project_desc_trgm` | 索引存在；`q=海报` 查询 114ms |
| P1-2 | `V42`：`idx_creative_asset_owner_active`（`status <> 'DELETED'` 部分索引） | EXPLAIN 走该索引 |
| P1-4 | `V42`：`idx_agent_run_user_message`、`idx_agent_run_assistant_message`、`idx_creative_asset_link_target` | 索引存在 |
| P1-5 | `V42`：`agent_tool_confirmation.created_at/decided_at` → `timestamptz` | 类型核对通过 |
| P0-3 | `V43`：backfill + `next_dispatch_at DEFAULT now()`；索引改 `(engine_version, control_state, next_dispatch_at, id) WHERE phase='QUEUED'`；claim SQL 去掉 `COALESCE` | `enable_seqscan=off` 下为 **Index Only Scan，无 Sort** |
| P0-2 | `KnowledgeVectorSchemaInitializer`：metadata json→jsonb（实测 23ms）+ `idx_kv_scope`、`idx_kv_document`、`idx_kv_content_trgm`、`idx_kv_metadata_gin`；不走 Flyway（新库表由 PgVectorStore 运行时创建） | 类型 jsonb、4 个索引就绪 |
| P2 | compose：`shared_buffers=768MB`、`work_mem=16MB`、`maintenance_work_mem=256MB`、`effective_cache_size=3GB`、`random_page_cost=1.1`、`effective_io_concurrency=200`、postgres `mem_limit=2g`；`shared_preload_libraries=pg_stat_statements` + 扩展已建；`ALTER DATABASE ... hnsw.ef_search=100` | 参数与 `pg_stat_statements` 均核对生效 |

- 未采纳：删除 `uq_creative_project_identity` / `uq_agent_run_owner_composite` —— 前者是多个子表复合外键的依赖，
  后者服务于 owner+fencing 的条件更新，删除会带来无谓风险。
- PostgreSQL 重启期间派发器出现一次瞬时 `DataAccessResourceFailureException`（连接重建），属预期，已自愈。
- 数据库备份：发布脚本在检测到迁移变更时自动 `pg_dump`（本次已备份）。
