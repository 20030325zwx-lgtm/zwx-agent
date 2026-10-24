# 会话摘要：n8n 试点边界加固（2026-10-24 下午）

被本文件接续的前置背景见 `session-2026-10-24-n8n-poc.md`（其遗留事项 1-4 已在本轮处理）。

## 改动内容

1. **管理员清理**：删除全部合成测试文档（目标 2 条 + 早期冒烟残留 3 条 = 5 条），DB 文档/block/向量清零，OSS 原件与 MinerU 结果目录随删除接口清理，日志无告警。注意：本仓库 OSS 是阿里云远端桶（`app.oss.bucket=zwx-agent`），不是本地 MinIO。
2. **索引轮询有界化**（`document-ingestion-live.json`）：
   - 新增 Set 节点 `更新轮询计数`（`pollAttempts` 跨循环累加，借助 Wait 节点透传）。
   - 新增 IF `索引失败或超时？`：`status==='FAILED'`（终态短路）或 `pollAttempts>=90`（≈15 分钟）。
   - 新增 `Stop and Error` 节点 `索引未就绪，执行失败`，错误信息含 documentId/次数/当前状态。
   - 原有 Code 节点全部替换为原生 Set/Stop and Error：n8n 2.44.6 internal task runner 存在 `requestExpired` 挂死问题，现工作流不依赖任务运行器。
   - 现网 workflow id 变更为 `KD1TLvMaSHKKDgaX`（CLI 重导入产生新 id 属预期），旧副本全部归档，仅保留模拟样例与现网真实流程。
3. **上传幂等键**（后端 + n8n）：
   - V22 迁移：`agent_knowledge_document.idempotency_key` + `(tenant_id, agent_key, idempotency_key)` 部分唯一索引。
   - `upload()` 七参重载：同键先查返回既有文档；INSERT 唯一冲突捕获后回查返回赢家；空键等价无键。
   - `AiController` 上传端点接受 `Idempotency-Key` 头（`[A-Za-z0-9_-]{1,64}`，非法 400）。
   - n8n：上传节点加 `Idempotency-Key: n8n-{{ $execution.id }}` 头，令牌/上传节点开 `retryOnFail`（3 次）。
4. **设计文档**（先文档后实施）：
   - `docs/design/2026-10-24-knowledge-approval-entry.md`：审批入口身份校验（REVIEWER 角色 + 分配表）、审计表、恢复凭据生命周期、n8n 收尾化改造与实施顺序（迁移编号 V23 起）。
   - `docs/design/2026-10-24-knowledge-upload-idempotency.md`：幂等契约与验收记录。
   - `docs/bugs/bug-2026-10-24-n8n-polling-gate-wiring.txt`：本轮接线 bug 与挂死根因记录。

## 验证结果

- 超时分支：cap=1 + 1s 首等变体，执行 #25 以 error 结束，消息含 documentId/轮询次数/状态。
- 正常闭环：执行 #26 上传→READY→人工审核→退回，success，REJECTED+意见落库；执行 #28 同链路通过→ACTIVE，`idempotency_key=n8n-28` 落库。
- 幂等 e2e：同键两次上传返回同一 documentId；无键行为不变；非法键 400。
- `mvn test`：217 个测试全绿（含 3 个新幂等单测）；后端重建并经 launchctl 重启，Flyway 至 V22。
- 关键排障结论：曾出现"人工恢复后执行永远 running"，根因是超时判断被误接到 READY 的 TRUE 分支（未就绪路径绕过判断 + READY 后并行轮询链永不收敛）；修正接线后挂死消失。排查期间验证了：定时等待恢复正常、最小 webhook 等待恢复正常、Code 节点任务运行器会 `requestExpired` 挂死（已在 bug 记录中）。
- 验证期间产生的全部合成文档（7 条）已用管理员身份删除，agent test 范围文档/向量/block 计数为 0。

## 遗留事项

1. 审批入口按 `2026-10-24-knowledge-approval-entry.md` 实施（REVIEWER 角色、分配表、审计表、resume-info 登记与后端触发恢复、n8n 收尾化）——设计已就绪，未动代码。
2. n8n 2.44.6 镜像内 task runner 不稳定：若未来需要 Code 节点，先试升级镜像或 external runner 模式。
3. 幂等键已知限制：并发同键竞态可能产生孤儿 OSS 对象（顺序重试场景无此问题）；键无过期清理。
4. 正式整合建议（第 5 项）：见下。

## n8n 试点正式整合建议（结论）

**不建议将 n8n 纳入正式部署；建议保留为本地试点。** 理由：

- 审批入口设计落地后，n8n 的不可替代价值只剩通知收尾与可视化画布；解析、切片、索引、状态机、重试、幂等全部是后端既有能力，"MinerU 异步轮询→READY→待审"编排用 Spring 既有设施（`@Scheduled`/事件）即可承载，不需要新增常驻第三方服务。
- 运维成本实测偏高：internal task runner 挂死、恢复凭据生命周期、license（Sustainable Use License）评估、以及本次暴露的版本快照/CLI 导入心智负担。
- 若产品上强需要"非开发人员可视化维护流程"，n8n 价值回升，届时按审批设计 §8 的收窄角色接入（n8n 不碰审批决定）再评估。

## 环境注意事项

- n8n 工作流更新固定流程：改仓库 JSON → `docker cp` 进容器 → `n8n unpublish:workflow`（旧）→ `n8n import:workflow --projectId=U7PVZEh5wHym3aau` → `n8n publish:workflow`（新）→ `docker restart`；不要直接改 `workflow_entity.nodes`（发布版本快照不跟随）。
- 后端改动后必须 `mvn -DskipTests package` 再 launchctl kickstart。
- `application-local.yml` 未改动；测试账号 admin 本地凭据见 `session-2026-09-05-hardening.md`。
