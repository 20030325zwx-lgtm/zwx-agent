# 会话摘要：数据库查询效率优化（2026-11-05）

## 本次改动（5 项，均已上线 0.7.14）

1. **列表瘦身**（59dc080）：`WorkflowService.list` 只取展示所需列（不再拉 `input`/`graph_snapshot`）；
   `AgentRunService.findScopedRuns/findRecentRuns` 使用 `SLIM_COLUMNS`（排除 `activities`/`sse_events`，
   单行约省 11KB）。详情接口保持整行读取。
2. **索引补强**（3729ffc / `V42`）：`pg_trgm` 扩展 + 素材名/项目名/项目简介三个 trigram 索引；
   素材列表 `status <> 'DELETED'` 部分索引；补 `agent_run.user_message_id/assistant_message_id`、
   `creative_asset_link.target_asset_id` 外键索引；`agent_tool_confirmation` 两个列改 `timestamptz`。
3. **派发队列**（a015887 / `V43`）：回填 `next_dispatch_at`、设默认 `now()`；
   索引换成 `(engine_version, control_state, next_dispatch_at, id) WHERE phase='QUEUED'`；
   claim SQL 去掉 `COALESCE(...)`，`enable_seqscan=off` 下确认为 Index Only Scan 且无 Sort。
4. **向量表 jsonb**（7b9cd39）：`KnowledgeVectorSchemaInitializer` 启动时把
   `agent_knowledge_vector.metadata` 从 `json` 校正为 `jsonb`（23ms），并补 `idx_kv_scope`、
   `idx_kv_document`、`idx_kv_content_trgm`、`idx_kv_metadata_gin`。不走 Flyway：新库该表由 PgVectorStore 运行时创建。
5. **实例参数**（07a31f3）：postgres `mem_limit=2g`；`shared_buffers=768MB`、`work_mem=16MB`、
   `maintenance_work_mem=256MB`、`effective_cache_size=3GB`、`random_page_cost=1.1`、
   `effective_io_concurrency=200`；预加载并启用 `pg_stat_statements`；`ALTER DATABASE ... hnsw.ef_search=100`。

## 验证

- 迁移：`flyway_schema_history` 至 V43 全部 success；发布脚本已自动 `pg_dump` 备份。
- 索引：`idx_creative_asset_owner_active` 被列表查询实际选中；`idx_creative_project_name_trgm` 等存在。
- 队列：`enable_seqscan=off` 时 `Index Only Scan using idx_agent_run_queue`，计划中无 Sort。
- 向量表：`metadata` 为 `jsonb`，4 个索引就绪；转换日志：`metadata json → jsonb 完成，耗时 23 ms`。
- 参数：`shared_buffers=768MB`、`work_mem=16MB`、`random_page_cost=1.1`、`effective_io_concurrency=200`、
  `hnsw.ef_search=100`、`pg_stat_statements` 可用（已记录语句）。
- 接口冒烟（公网）：health ok、素材搜索 114ms、项目列表 49ms、runs/recent 75ms、工作流列表 160ms。
- 观察：重启 PostgreSQL 时派发器出现一次瞬时连接错误，随后自愈。

## 遗留

- 未删除「冗余索引」：`uq_creative_project_identity` 是多个子表复合外键的依赖，
  `uq_agent_run_owner_composite` 服务于 owner+fencing 条件更新，删除无收益只有风险。
- 事件流表（`agent_execution_event`/`media_job_event`/`agent_llm_call`）分区与 TTL 留到量级上来再做。
- `workflow_run.steps/artifacts`、`creative_canvas.graph` 的「宽列拆子表」也未做（当前列表已瘦身，收益有限）。
- 后续可用 `pg_stat_statements` 按真实耗时排序，再做第二轮；注意重启会清空统计。
