# 知识文档待审事件推送 n8n（2026-10-25）

> 状态：**已实施并验证**（2026-10-25 凌晨；后端事件推送、n8n 监听工作流 `hqrJUWFuFM1SgQhT`、前端"需人工审核"开关全部落地，happy/reject/负向 e2e 通过，测试文档已清理）。目标：UI 上传的知识文档进入待审（READY + 需人工审核）时，后端推送事件触发 n8n 监听工作流，让审批过程在 n8n 侧可视化——**一条执行 = 一份文件 = 一个案件**。审批决定权仍在后端实名 REVIEWER（见 `2026-10-24-knowledge-approval-entry.md`），n8n 只做"登记恢复地址 → 等待 → 查询真实状态 → 收尾"。

## 1. 背景

审批治理（V23）落地后，人工审批的权威记录在后端，但入口只有知识库页面的 READY 列表；n8n 的执行历史能提供案件级的过程可视化（挂起等待、恢复收尾、节点级结果），却只能被 n8n 自己的上传工作流触发。用户从知识库 UI 上传的文档 n8n 完全不可见。本设计补上"后端 → n8n"的事件推送通道。

## 2. 事件契约

- 触发点：`AgentKnowledgeDocumentService.indexDocument` 索引成功后 `!autoPublish` 分支（记录 SUBMIT 审计的同一位置）。该分支已被上传、reindex、索引恢复任务三个入口覆盖。
- 触发条件（全部满足）：
  1. `lifecycle_status` 刚流转为 READY（新版本索引成功）；
  2. `auto_publish = false`；
  3. `idempotency_key` 为空或不以 `n8n-` 前缀开头——n8n ingestion 工作流固定发送 `Idempotency-Key: n8n-{executionId}`（见 `2026-10-24-knowledge-upload-idempotency.md`），用此前缀区分来源，避免 n8n 上传的文档被重复开出第二个监听执行。**这是约定不是机制**：若未来修改 n8n 幂等键格式，需同步本判定。
- 事件为 at-least-once 通知（HTTP POST 到 n8n webhook），失败仅告警日志、不写审计——事件只是通知渠道，审批的权威记录始终是 `agent_knowledge_review_log` 与后端 READY 列表。

payload（POST JSON body）：

```json
{
  "eventId": "uuid",
  "type": "knowledge.review.pending",
  "documentId": "...",
  "tenantId": "default",
  "agentKey": "super",
  "filename": "员工手册v3.docx",
  "versionNo": 3,
  "submittedBy": "admin",
  "status": "READY",
  "occurredAt": "2026-10-25T08:00:00Z"
}
```

`filename` 与 `documentId` 是人眼识别案件的最低要求：n8n 执行列表不支持按 payload 搜索，打开执行详情后第一眼要能对上文件。

## 3. n8n 监听工作流（document-review-watch）

节点链（全原生节点，禁 Code——task runner `requestExpired` 挂死红线）：

```
接收待审事件(webhook POST /webhook/aster-document-review-notify)
  → 获取工作流令牌(POST /auth/token, Basic credential "Aster Workflow Service")
  → 登记恢复地址(POST .../resume-info, body={resumeUrl: $execution.resumeUrl, n8nExecutionId})
  → 等待审批决定(Wait webhook, limitWaitTime=7天)
  → 查询审批结果(GET workflow-status)
  → 审批已有结果？(lifecycleStatus ∈ {ACTIVE, REJECTED})
       ├─ true  → 流程收尾(Set: documentId/finalStatus/message)
       └─ false → 等待审批超时(Stop and Error, 带 documentId)
```

设计要点：

1. **恢复后不分支于 resume payload，只信后端状态**：无论 Wait 是被后端恢复还是 7 天时限自然到期，都执行同一个"查询审批结果"节点，以后端 `workflow-status` 的 `lifecycleStatus` 为准。这同时解决了两个边界：
   - **审批先于登记的竞态**（事件发出几秒内审批人就点了发布，`resume_webhook_url` 尚未登记，后端 `triggerAsync` 静默 no-op）：监听执行等到超时后查询状态，看到 ACTIVE，正常收尾；
   - **同一文档重建索引的旧监听执行**（会再触发一个新监听执行，旧执行无人恢复）：旧执行超时后查询真实状态收敛到最终结果，不需要事件撤销机制。
2. **恢复 URL 仅后端持有**：登记后不回显、不进日志（与既有 resume-info 端点行为一致）。
3. 后端调用 n8n 一律 **HTTP/1.1**（`bug-2026-10-24-n8n-java-httpclient-h2c-hang` 红线）。

## 4. 后端组件

- 新增 `N8nReviewEventNotifier`（仿 `N8nResumeNotifier` 骨架）：配置键 `app.notify.n8n.review-webhook-url`，留空即禁用（no-op）；JDK HttpClient 强制 HTTP/1.1、连接 5s / 请求 10s 超时；单线程 daemon executor；首发 + 3 次重试（5s/15s/60s，backoff 可注入）；最终失败仅 warn。
- `CurrentActor.requireKnowledgeWorkflowAccess` 放宽：WORKFLOW 允许 `tenant=default` 且 `agentKey ∈ {test, super}`（原硬编码仅 test）。放宽原因是前端知识库页面只有 `super` 一个 agentKey，监听工作流要在 super 范围登记恢复地址。审批动作仍由 `ReviewAccessGuard` 拦截（WORKFLOW 无审批权不变），即放宽的只是"上传/查状态/登记恢复地址/验证检索/TIMEOUT 事件"这组非决策类端点。
- n8n 侧 WORKFLOW 服务账号需被管理员分配为 (default, super) 的审批人？**不需要**——登记 resume-info 走 `requireKnowledgeWorkflowAccess`，审批走实名 REVIEWER；但 e2e 里需要有 REVIEWER 被分配到 (default, super) 才能完成审批动作。

## 5. 前端

- `KnowledgeAdmin.vue` 上传入口加"需人工审核"开关（默认关 = 现行为不变），勾选后上传带 `autoPublish=false`；`api/index.js` 的 `uploadAgentKnowledgeDocument` 增加该参数。
- 无前端测试基建，靠构建 + 手工/浏览器验证。

## 6. 边界与不做的事

- 事件洪泛防护靠"只推里程碑"：仅 READY 待审一个事件，INDEXING 过程不推。
- n8n 执行历史不作为全局看板；待办清单的权威视图仍是后端 `GET /agent-knowledge/documents`。
- 不做审批超时提醒、对账兜底（仍是 `2026-10-24-knowledge-approval-entry.md` 的第二期遗留）。
- n8n 侧去重不做：重复事件（重试）最多多一个等待执行，靠 7 天时限自愈收敛，试点量级可接受。
