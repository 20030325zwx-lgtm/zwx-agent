# 知识文档待审事件推送 n8n（2026-10-25）

> 状态：**已实施并验证**（2026-10-25 凌晨；v1 为 READY 开案，当日追加 v2 重构：**案件在上传时即打开，索引完成后由后端唤醒并回填三阶段指标**，见 §7。现网工作流 id `lg86ItRGcoPJYgij`）。目标：UI 上传的知识文档全过程在 n8n 侧可视化——**一条执行 = 一份文件 = 一个案件，画布呈现 ①文档解析 ②结构化切片 ③向量化入库 三个阶段**。审批决定权仍在后端实名 REVIEWER（见 `2026-10-24-knowledge-approval-entry.md`），n8n 只做"登记恢复地址 → 等待 → 查询真实状态 → 收尾"。

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

## 7. v2 重构：案件覆盖全生命周期、三阶段回显（2026-10-25 追加）

v1 的问题是案件在 READY 才打开，n8n 里看不到解析/切片/向量化过程。v2 改为：

```
上传 → 后端派"开案"事件（status=INDEXING）→ n8n 开案并登记阶段恢复地址 → Wait(30min)
     → 索引完成 READY → 后端经 resume_webhook_url 唤醒案件
     → n8n 查 workflow-status（已含索引指标）→
         已生效/已退回（自动发布路径）→ 直接收尾
         仍待审 → ①文档解析 ②结构化切片 ③向量化入库（真实耗时/切片数）→ 登记审批恢复地址 → Wait(7天)
         → 发布/退回 → 后端唤醒 → 查状态 → 收尾
```

- **指标持久化**：V24 给 `agent_knowledge_document` 加 `parse_duration_ms` / `vector_duration_ms`（解析阶段含切片构建；向量化阶段含 PGVector 写入），由 `indexDocument` 的 READY 更新一并写入；`workflow-status` 响应追加 `parseDurationMs/vectorDurationMs/chunkCount`。
- **三阶段展示数据以 workflow-status 为准**（状态优先原则不变），恢复 POST body 里的指标仅作冗余。
- **小文件竞态**（索引 0.6s 快过 n8n 登记 URL 的 1-2s）：`N8nResumeNotifier.trigger` 把"查 URL"纳入重试循环（5s/15s/60s），查到才 POST——修复了 v2 首轮 e2e 发现的案件卡在等索引的缺陷。
- **开案事件移到 upload**（幂等键非 n8n- 前缀即开案，含 autoPublish=true 的文档）；`notifyPendingReview` 只记 SUBMIT 审计 + 触发案件唤醒（同样仅非 n8n 来源，防止 resume 打进 ingestion 工作流的审批 Wait）。
- `N8nResumeNotifier.triggerAsync` 签名收敛为 `triggerAsync(documentId)`，租户/agent 从库行读取；恢复 payload 携带 filename/chunks/parseMs/vectorMs。
- 重建索引（ACTIVE/ARCHIVED 文档）不重复开案、不触发唤醒；FAILED 重试成功的文档没有开案（案件随 30 分钟超时止于失败态，属已知边界）。
- v2 e2e：上传同秒开案 → 索引完成自动唤醒 → ①②③ 节点带真实数据（小文件 0.1s/1 切片/0.3s）→ 发布后收尾 success；n8n 来源上传不产生案件。
