# 知识文档上传幂等键设计

日期：2026-10-24
状态：设计稿（随本批改动实施）
关联：`docs/design/2026-10-24-knowledge-approval-entry.md`（其中 V22 编号顺延为 V23）、`session-2026-10-24-n8n-poc.md`

## 1. 背景

n8n 上传链路对"请求超时但服务端实际已处理"的重试不安全：`POST /agent-knowledge/documents` 每次调用都会生成新文档 ID 并插入新版本（`version_no` 递增），超时重试会产生重复版本。因此试点一直未开启上传自动重试。

已有先例：聊天消息用 `client_request_id` 列 + 部分唯一索引防重（重复即 409，`V5__add_message_status_and_idempotency.sql`）。上传场景语义不同：**重试方想要的是第一次请求的结果**（拿到同一个文档 ID），而不是报错。

## 2. 契约

- 请求头 `Idempotency-Key`（可选，1-64 字符，`[A-Za-z0-9_-]`；非法值 400）。
- 作用域：`(tenant_id, agent_key, idempotency_key)`。
- 行为：
  - 首次请求：正常处理，文档行记录该键。
  - 同键重试（无论首次是成功、进行中还是已失败后文档已落库）：**返回已存在的文档记录**（HTTP 200，body 为该文档当前状态），不创建新版本、不重新解析。
  - 未携带键：行为与现状完全一致（不强制，兼容旧调用方）。

## 3. 存储

复用文档表（一行文档对应一键，天然 1:1）：

```sql
-- V22__add_knowledge_upload_idempotency.sql
ALTER TABLE agent_knowledge_document ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_document_idem
    ON agent_knowledge_document (tenant_id, agent_key, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

`GET .../workflow-status` 等响应不回显该键（内部字段）。

## 4. 实现要点

1. `upload()` 增加 `idempotencyKey` 参数；入口先按键查现有文档，命中即返回。
2. 未命中则正常生成文档，INSERT 时带上键；并发同键冲突由唯一索引兜底，捕获 `DataIntegrityViolationException` 后重查返回既有记录。
3. 已知限制（接受并记录）：并发同键竞态时，落败方可能已上传 OSS 对象（孤儿对象，按前缀清理可回收）；本设计的重试场景是"超时后顺序重试"，不是并发同键。INSERT 前置于 OSS 上传的重构（先占键再传对象）留待出现真实并发需求时再做。
4. 键不做过期清理（试点数据量小）；正式接入时若需要，加"7 天前的键置空"对账任务即可释放约束。

## 5. n8n 侧重试配置

后端就位后，`document-ingestion-live.json`：

- `MinerU 解析并切片入库`：`retryOnFail=true, maxTries=3, waitBetweenTries=5s`（节点内建重试，原生执行，不经过任务运行器）。
- 请求头增加 `Idempotency-Key: n8n-{{ $execution.id }}`——同一 n8n 执行内重试键稳定，不同执行互不影响。
- `获取工作流令牌`：`retryOnFail=true, maxTries=3, waitBetweenTries=2s`。
- 重试语义验证：模拟"第一次请求后端 500/超时，重试同键"，确认第二次返回同一 documentId 且版本号不变。

## 6. 验收清单（2026-10-24 已验证）

- [x] 同键两次上传（顺序）：两次返回同一 documentId（0b59b981-…）、同一 version_no；无新版本产生。
- [x] 无键上传：行为与现状一致（新文档）。
- [x] 非法键（含空格/特殊字符）：400。
- [x] n8n 打开上传重试后，正常路径不受影响：执行 #28 全链路 success，`idempotency_key=n8n-28` 落库，审批发布 ACTIVE。
- [x] `mvn test` 全绿（217 个测试，含 3 个幂等单元测试：命中重放、唯一约束竞态回退、空键等价无键）。
