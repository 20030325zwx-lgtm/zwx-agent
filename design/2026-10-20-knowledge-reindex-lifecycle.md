# 知识库重建索引与发布状态

> 状态：已实施（2026-10-20）。

## 1. 问题

已生效（`ACTIVE`）文档执行“重建索引”后，索引成功的 SQL 无条件把 `lifecycle_status` 写成 `READY`，导致：

- 文档从“生效中”变成“待发布”；
- 检索立即失去该文档；
- 前端没有发布入口，用户无法恢复。

## 2. 生命周期规则

| 重建前 | 索引成功后 | 是否自动发布 |
| --- | --- | --- |
| `INDEXING`（新上传版本） | `READY` | 是，自动 publish → `ACTIVE` |
| `ACTIVE`（已生效） | `READY` 索引状态 + `ACTIVE` 生命周期 | 不改变生效状态 |
| `ARCHIVED`（已归档） | `READY` 索引状态 + `ARCHIVED` 生命周期 | 保持归档 |
| `READY`（待发布） | `READY` | 不自动发布；用户点击“发布” |

实现：

```sql
lifecycle_status = CASE WHEN ? = 'INDEXING' THEN 'READY' ELSE ? END
```

## 3. 发布接口与前端

```text
POST /api/ai/agent-knowledge/documents/{documentId}/publish?agentKey=...
```

- ADMIN 专属。
- 仅 `READY` / `ACTIVE` 可发布。
- 发布时旧 `ACTIVE` 版本 → `ARCHIVED`，目标版本 → `ACTIVE`，并翻转切片 metadata。

前端知识库管理：

- 选中 `READY` 文档时，顶部显示“发布待发布版本”按钮；
- 发布成功后刷新列表与详情；
- 已生效文档不再出现“待发布”。

## 4. 验证

- 简历文档重新索引后从 9 chunks 变为 10 chunks；
- 执行 publish 后 `lifecycleStatus=ACTIVE`；
- 再次 reindex：仍为 `READY` 索引状态 + `ACTIVE` 生命周期，不再变成待发布；
- 前端构建通过。
