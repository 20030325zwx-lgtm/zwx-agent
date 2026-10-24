# 知识文档人工审批入口设计：身份校验、审批人分配与审计记录

日期：2026-10-24
状态：**已实施并验证**（2026-10-24 下午；V23 迁移、REVIEWER 角色、分配/审计/恢复触发、n8n 收尾化全部落地，联合 e2e 通过）
决策记录：经评估决定**保留 n8n**（编排+通知角色，审批决定回归后端）；试点镜像已升级 2.45.3。
关联：`evaluation/n8n-document-poc`（n8n 试点）、`session-2026-10-24-n8n-poc.md`、`docs/bugs/bug-2026-10-24-n8n-polling-gate-wiring.txt`、`docs/bugs/bug-2026-10-24-n8n-java-httpclient-h2c-hang.txt`

实施差异说明（与原设计稿）：

- `requireReviewAccess` 未放进 `CurrentActor`（其为纯值对象），改为独立 `ReviewAccessGuard` 组件，行为一致。
- 管理接口实现为 `/api/ai/agent-reviewers`（GET/POST/DELETE），分配时普通用户自动升级 REVIEWER，WORKFLOW 服务账号拒绝。
- 超时审计事件端点实现为 `POST .../review-events`，仅接受 `TIMEOUT`。
- 恢复触发固定 `{"phase":"finalize"}` payload，不做新旧协议兼容层（两侧同步部署）。

## 1. 背景与问题

n8n 试点中，人工审批通过 n8n Wait 节点的 webhook 恢复实现：任何拿到恢复 URL 的人 POST `{"approved":true}` 即可完成审批。该 URL 是"恢复能力凭据"，存在三个缺口：

1. **无身份校验**：恢复请求不经过任何认证，无法证明审批人是谁。
2. **无审批人分配**：没有"谁能审批哪个租户/agent 的文档"的映射。
3. **无审计记录**：publish/reject 只改文档状态与 `review_note`，不记录操作者、渠道、时间线；`review_note` 会被覆盖，不可追溯。

同时当前 n8n 流程在恢复后由**工作流服务身份（WORKFLOW）**调用后端 publish/reject——即"系统身份替人做决定"，职责错位。

## 2. 目标与非目标

目标（第一期）：

- 审批动作（发布/退回）必须以实名身份调用后端 API，后端是审批决定的唯一权威记录点。
- 建立"审批人分配"：明确哪些用户可以审批哪个 tenant/agent 范围的文档。
- 每次审批动作产生不可篡改的追加式审计记录，可按文档查询完整审批时间线。
- n8n 只负责编排与通知；恢复 webhook 由后端在审批落库成功后触发，恢复凭据仅后端持有。

非目标（明确推迟）：

- 不做审批表单 UI、消息推送/邮件通知、移动端审批。
- 不做多级审批、会签、委托代理审批。
- 不做 SSO/IAM 集成（沿用现有用户名密码 + JWT）。

## 3. 方案总览

审批主流程改为"后端前置"：

```
审批人（UI/API，实名 JWT）
  → POST /api/ai/agent-knowledge/documents/{id}/publish|reject
  → 后端：校验审批资格 → 事务内更新状态 + 写审计 → 触发 n8n 恢复（收尾通知）
  → n8n：继续收尾（如通知上传者），不再调用 publish/reject
```

关键原则：

1. **决定在后端，编排在前端**：publish/reject 的语义（含 approved/reviewNote）由实名请求承载；n8n 恢复只是"继续执行收尾节点"的信号。
2. **恢复 URL 是机密**：n8n Wait 恢复 URL 由后端持有（按文档存储），不写入日志、不返回给前端、不出现在审计备注里；泄露即失效（见 5.4）。
3. **审计只追加**：审计表无 UPDATE/DELETE 路径，应用层不提供修改接口。

## 4. 身份校验设计

### 4.1 角色模型

沿用 `app_user.role`（USER/WORKFLOW/ADMIN），新增 `REVIEWER` 角色：

| 角色 | 审批权限 |
|------|----------|
| ADMIN | 全部范围（保持现状，兜底） |
| REVIEWER | 仅"审批人分配表"中授权的 (tenant_id, agent_key) 范围 |
| USER / WORKFLOW | 无审批权限（WORKFLOW 不再允许调用 publish/reject） |

`CurrentActor` 新增：

```java
public void requireReviewAccess(String agentKey) {
    if (isAdmin()) return;
    if (role != Role.REVIEWER || !reviewerAssignmentService.isAssigned(tenantId, agentKey)) {
        throw new AccessDeniedException("Review access denied");
    }
}
```

现有 `requireKnowledgeWorkflowAccess`（WORKFLOW + default/test 硬编码）保留用于上传/状态查询/检索验证等**非决策类**端点；publish/reject 改挂 `requireReviewAccess`。

### 4.2 审批人分配

新表 `agent_reviewer`（V23）：

```sql
CREATE TABLE agent_reviewer (
    id          VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    agent_key   VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    assigned_by VARCHAR(64)  NOT NULL,          -- 操作的管理员 user_id
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, agent_key, user_id)
);
```

管理接口（仅 ADMIN）：

- `GET  /api/ai/agent-reviewers?agentKey=`：列出分配。
- `POST /api/ai/agent-reviewers`：`{tenantId?, agentKey, username}`（tenantId 默认当前管理员租户；用户必须存在且为 REVIEWER 角色或升级其角色）。
- `DELETE /api/ai/agent-reviewers/{id}`：移除分配（软约束：仅影响后续审批，不影响审计）。

被分配用户的用户角色需要是 REVIEWER；分配与角色是两层：角色决定"可以成为审批人"，分配决定"审哪个范围"。

## 5. 审计记录设计

### 5.1 审计表

新表 `agent_knowledge_review_log`：

```sql
CREATE TABLE agent_knowledge_review_log (
    id             VARCHAR(64) PRIMARY KEY,   -- UUID
    tenant_id      VARCHAR(64) NOT NULL,
    agent_key      VARCHAR(64) NOT NULL,
    document_id    VARCHAR(64) NOT NULL,
    action         VARCHAR(24) NOT NULL,      -- SUBMIT/PUBLISH/REJECT/TIMEOUT/RESUME_FAIL
    actor_user_id  VARCHAR(64),               -- 系统动作为 NULL
    actor_username VARCHAR(64),
    actor_role     VARCHAR(16),
    channel        VARCHAR(16) NOT NULL,      -- API/N8N/SYSTEM
    note           TEXT,                      -- 审核意见或系统说明
    n8n_execution_id VARCHAR(64),             -- 关联 n8n 执行（可空）
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_review_log_document ON agent_knowledge_review_log (document_id, created_at);
```

记点说明：

- `SUBMIT`：文档进入待审（READY 且 auto_publish=false）时由系统写入，channel=SYSTEM。
- `PUBLISH` / `REJECT`：审批动作成功落库后写入，channel=API，note=本次审核意见。
- `TIMEOUT`：轮询超时/索引失败终止时由 n8n 侧凭据调用（见 5.3）或后端对账任务写入，channel=N8N/SYSTEM。
- `RESUME_FAIL`：审批落库成功但 n8n 恢复调用失败时写入，channel=SYSTEM。

### 5.2 服务层改动

`AgentKnowledgeDocumentService.publish/reject` 增加 actor 参数（`CurrentActor` 透传），在状态更新的同一事务内写审计行；拒绝时的 `review_note` 同时写入审计（文档表字段保留为"当前意见"，审计为"全部意见历史"）。

### 5.3 工作流侧记点

n8n 超时/失败分支目前只有执行错误。第一期允许 WORKFLOW 身份调用一个受限新端点 `POST /agent-knowledge/documents/{id}/review-events`（body: `{action: TIMEOUT, note}`），仅能追加 `TIMEOUT` 事件、不能改变文档状态，作为审计补充。对账兜底（后端定时把超时未审的 READY 文档写入审计）推迟到第二期。

### 5.4 恢复凭据生命周期

- 文档表新增列（V23 迁移）：`resume_webhook_url TEXT`、`resume_expires_at TIMESTAMPTZ`。
- n8n 上传流程在暂停前，把恢复 URL 与执行 ID 通过**仅限 WORKFLOW 的**新端点 `POST /agent-knowledge/documents/{id}/resume-info` 登记到后端（与现有 workflow-status 模式一致：工作流身份只写自己范围的数据）。
- 审批动作成功后，后端异步触发该 URL（POST，不携带敏感 payload）；失败重试 3 次（间隔 5s/15s/60s），仍失败则记 `RESUME_FAIL`，文档状态不受影响（状态已落库，n8n 收尾只是通知）。
- 安全约束：该列不进任何 API 响应（序列化排除）；日志脱敏；n8n Wait webhook URL 过期策略与轮换随 n8n 版本能力评估，第一期依赖"URL 仅后端持有 + 按文档隔离"。

## 6. 接口变更汇总

| 接口 | 变更 |
|------|------|
| `POST /agent-knowledge/documents/{id}/publish` | 权限从 `requireKnowledgeWorkflowAccess` 改为 `requireReviewAccess`；写审计；成功后触发 n8n 恢复 |
| `POST /agent-knowledge/documents/{id}/reject` | 同上 |
| `GET /agent-knowledge/documents/{id}/review-log` | 新增：REVIEWER/ADMIN 查询审计时间线 |
| `GET/POST/DELETE /agent-reviewers...` | 新增：审批人分配管理（ADMIN） |
| `POST /agent-knowledge/documents/{id}/resume-info` | 新增：WORKFLOW 登记恢复 URL（机密，不回显） |
| `POST /agent-knowledge/documents/{id}/review-events` | 新增：WORKFLOW 追加 TIMEOUT 审计事件 |

n8n 工作流对应改造：恢复后节点不再调用 publish/reject，改为"结束/通知"节点；恢复 payload 不再需要 `approved/reviewNote`（由后端在触发恢复前处理完毕）。兼容期可用恢复 payload 里的 `phase: finalize` 区分新旧协议。

## 7. 迁移与实施顺序

- V23 迁移（V22 已被上传幂等键占用，见 `2026-10-24-knowledge-upload-idempotency.md`）：`agent_reviewer` 表、`agent_knowledge_review_log` 表、`agent_knowledge_document` 增加 resume 两列。
- 实施顺序：迁移 → 角色/分配服务与接口 → publish/reject 挂新权限 + 审计 → resume-info/恢复触发 → n8n 工作流收尾化改造 → 联调（含越权用例：USER/WORKFLOW 调 publish 必须 403）。
- 试点阶段 `REVIEWER` 用户由管理员 SQL/接口创建（如 `reviewer1`），分配到 `default/test`。

## 8. 与 n8n 试点整合决策的关系

本设计落地后，n8n 的角色收窄为"解析/索引编排 + 通知"，人工审批完全回到后端主应用。这决定了整合评估的结论方向：若继续采用 n8n，它不再触碰审批决定， Licensing/安全暴露面显著缩小；若放弃 n8n，后端自建"解析→索引→待审"的任务队列即可替代（解析与索引已是现有服务能力）。该评估在第 4、5 项任务完成后由后端实现工作量对比决定。

## 9. 未决问题

1. 审批超时提醒（decision_deadline + 定时任务）的优先级——第二期。
2. `review-events` 是否需要防重放（n8n 侧凭据 + 幂等键）——实施时与任务 4 的幂等键设计合并考虑。
3. 多人审批同一文档的并发：当前以 `lifecycle_status=READY` 条件更新 + 事务保证"先到先得"，第二个动作返回 409；是否需要乐观锁版本号——实施时评估。
