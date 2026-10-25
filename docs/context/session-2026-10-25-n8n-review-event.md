# 会话摘要：知识文档待审事件推送 n8n（2026-10-25 凌晨）

接续 `session-2026-10-24-approval-governance.md`。用户决策：**保留 n8n（编排+通知角色）**，并把"UI 上传 → 待审 → n8n 可视化"打通（一次执行 = 一份文件 = 一个案件）。实施前已清理 n8n 全部 33 条历史执行（测试数据）。

## 改动内容（按 `docs/design/2026-10-25-knowledge-review-event-notify.md` 实施）

1. **后端事件推送**：新增 `N8nReviewEventNotifier`（配置 `app.notify.n8n.review-webhook-url`，留空禁用；强制 HTTP/1.1；单线程 daemon + 首发 3 次重试 5s/15s/60s，失败仅 warn）。挂点在 `AgentKnowledgeDocumentService.notifyPendingReview(documentId)`（从 indexDocument 的 `!autoPublish` 分支抽出，SUBMIT 审计同一出口）：幂等键不以 `n8n-` 开头才推送事件（区分 n8n ingestion 来源，避免重复开案件）。
2. **WORKFLOW 权限放宽**：`CurrentActor.requireKnowledgeWorkflowAccess` 允许 WORKFLOW 操作 `tenant=default` 且 `agentKey ∈ {test, super}`（审批决定仍由 ReviewAccessGuard 拦截不变）。
3. **n8n 新工作流** `document-review-watch.json`（现网 id `hqrJUWFuFM1SgQhT`，已发布）：webhook `aster-document-review-notify` → 换 WORKFLOW 令牌 → 登记 resume-info（agentKey 取自事件 payload，支持 super）→ Wait 挂起（**limitWaitTime 7 天**）→ 恢复后一律查 workflow-status，`lifecycleStatus ∈ {ACTIVE, REJECTED}` 才收尾，否则 Stop and Error——状态优先设计自愈"审批先于登记"竞态与重建索引的旧监听执行。复用 credential `uIzyXF4W3Pavv6a1 / Aster Workflow Service`。注意：导入需要节点带 `position` 字段。
4. **前端**：`KnowledgeAdmin.vue` 上传旁加"需人工审核"开关（默认关=原行为），透传 `autoPublish`；`api/index.js` 上传函数加第 4 参；上传后有 6s 闪现提示（已发布/已提交人工审核）。
5. **application-local.yml 纯增量**：`app.notify.n8n.review-webhook-url: http://127.0.0.1:5678/webhook/aster-document-review-notify`（唯一触碰，其余键未动）。

## 验证结果

- `mvn test` 228 个全绿（新增 4 个：notifyPendingReview 的 SUBMIT+事件/ n8n- 前缀不推送；notifier 禁用开关/payload 结构）。期间踩坑：**target/classes 里 207 个 IDE 复制残留的 "X 2.class" 陈旧类被组件扫描**导致 @SpringBootTest 全挂，删除后恢复（见 `docs/bugs/bug-2026-10-25-stale-duplicate-classes-in-target.txt`）。
- e2e（全部走真实 API）：管理员把 smokeuser 分配为 (default, **super**) 审批人 → 上传（autoPublish=false）→ READY + SUBMIT 审计 + 日志确认事件推送 → **n8n 执行 #35 waiting（payload 含 filename/documentId）** → smokeuser 发布 → ACTIVE → **#35 自动恢复 success 收尾**，恢复凭据清空。退回路径：#36 waiting → REJECT 带意见 → #36 success，审计 SUBMIT+REJECT 齐全。
- 负向：autoPublish=true（默认）上传不产生监听执行；幂等键 `n8n-e2e-neg-1` + autoPublish=false 只有 SUBMIT 审计无事件（事件日志恰 2 条 = 两个真实案件）。
- 清理：4 份 e2e 文档经管理员 API 删除（向量/block/OSS 级联）；n8n probe 执行 #34 已删，保留 #35/#36 作为演示案件；前端 3000 与后端 health 均正常。

## 遗留事项

1. UI 上传只有"需人工审核"开关，**审批动作仍需调 API**（审批待办列表/审批按钮 UI 未做，沿用 10-24 遗留）。
2. n8n Wait 的 7 天 limitWaitTime 参数形态（`limitType: afterTimeInterval / resumeAmount / resumeUnit`）按 n8n 导出惯例写，未实测 7 天超时分支（正常路径已验证，超时自愈是兜底）。
3. 事件失败仅 warn 日志不进审计（设计取舍，见设计文档 §2）。
4. 旧 INDEXING 卡死文档（8bc5fe5a 等历史状态）与本次改动无关，未处理。

## 环境注意事项

- n8n 现有两个真实工作流：ingestion `d7AZrTUp2o1QxTLG`、待审监听 `hqrJUWFuFM1SgQhT`（均 2.45.3，后者依赖 WORKFLOW 账号 super 权限）。
- 关掉事件推送：把 `app.notify.n8n.review-webhook-url` 置空即可，链路其余部分不受影响。
- smokeuser 现为 (default, test) 和 (default, super) 两处 REVIEWER（试点状态保留）。
- `application-local.yml` 仅新增 notify 键，其余未动。

## 追加（同日后续）：G60 大 Excel 索引慢排查与向量阶段改造

- 现象：用户上传 `G60沪昆高速镇胜线交通事故_前后门架梳理.xlsx`（348KB，2290 个行级切片）索引 2 分 05 秒，期间 UI 只显示"0 切片·处理中"。实为**正常完成**（表格行级切片是设计行为），瓶颈全部在远端 API。
- 耗时实测（新增阶段日志后）：MinerU 解析 ~44s（在线 API 异步队列）+ embedding 92 批（25 条/批，DashScope 配额限速）~80s。
- 改造：抽出 `storeVectors`（批大小 25 不变），加 `app.knowledge.index.embedding-parallelism` 并行旋钮 + 两阶段计时日志。**实测 4 路并行反而 125s**：撞 429 `Throttling.AllocationQuota`（该账号 embedding 配额低），重试拖慢——**默认回到 1（串行）**，配额充足的账号可调大。教训：远端配额是瓶颈时并行无用且有害。
- 过程中遭遇 **OrbStack VM 卡死**（docker 全挂、新 DB 连接超时），`orbctl stop && orbctl start` 恢复，见 `docs/bugs/bug-2026-10-25-orbstack-vm-wedge.txt`。
- G60 文档现状：用户已自行发布 → lifecycle ACTIVE、n8n 案件 #37 success 收尾；验证用副本（dd3cfdd4）及其案件 #38 已删除，n8n 保留 3 条案件（#35/#36/#37）。

## 追加（同日再后续）：v2 案件三阶段回显

- 用户反馈 v1 案件"只有等审批+收尾，看不到解析/切片/向量化"。v2 重构（见设计文档 §7）：**开案事件移到 upload**（status=INDEXING，autoPublish=true 也开案）；V24 持久化 `parse_duration_ms/vector_duration_ms`；`workflow-status` 追加指标；n8n 工作流改为双 Wait（等索引 30min + 等审批 7d），恢复后按状态分支，待审路径回显 **①文档解析 ②结构化切片 ③向量化入库** 三个带真实数据的节点。现网 id `lg86ItRGcoPJYgij`（旧 `hqrJUWFuFM1SgQhT` 已 unpublish）。
- 过程中发现并修复缺陷：**小文件索引（0.6s）快过 n8n 登记恢复 URL（1-2s）导致案件卡在等索引**——`N8nResumeNotifier.trigger` 原来只在重试循环前查一次 URL；改为查询纳入重试循环（5s/15s/60s）。`triggerAsync` 签名收敛为 `triggerAsync(documentId)`（租户/agent 从库行读）。
- v2 e2e：上传同秒开案（#40）→ 自动唤醒 → ①②③ 真实数据（0.1s / 1 切片 / 0.3s）→ 发布收尾 success；#39（卡住案件）经发布救回走"直接收尾"分支；n8n 来源（幂等键 n8n-*）不产生案件。测试文档已删（3 份），n8n 保留 #35-#40 六条案件记录。
- 230 个测试全绿；Flyway 至 V24；后端已重启上线。

## 追加（同日第三段）：Jev 意图分流批 0（观察模式）

- 用户提议引入意图识别（TypeSafe Jev / System One 决策模型）。设计见 `docs/design/2026-10-25-jev-intent-routing.md`：一次 Jev 调用三问并行（意图 choice + 复杂度 score + 是否需要私有知识库 noul），决策表按置信度路由（≥0.7 自动开检索开关、闲聊短路候选、<0.5 回退）。
- **批 0 落地为观察模式（不改变任何行为）**：新包 `platform/intent/`（IntentRouter 接口 + IntentDecision 决策/summary + JevIntentRouter 实现）；`AiController.doChatWithManus` 建图前 classify，结论经 `ManusRunRequest.prefaceActivity`（新增可空字段，resume 传 null）作为 activity 事件（phase=plan，agent=意图识别）推给前端 + INFO 日志攒对照数据；任何失败/禁用返回 empty = 现状行为。
- 配置 `app.intent.jev.*`（enabled/base-url/api-key/model/timeout-ms=1500）；**api-key 为空即实际禁用**——当前未配 Key，处于禁用态冒烟通过（SSE 正常、零 jev 调用）。**待用户从 console.typesafe.ai/keys 取 KEY 填入 `app.intent.jev.api-key` 后即生效**。
- 测试 236 个全绿（新增 6 个：parse/buildRequest/决策表/禁用态/不可达端点降级/summary）。待办：批 1（自动开开关）待观察期数据；中文 criteria 措辞待真实消息校准。
- **用户无法充值 TypeSafe → 追加 QwenIntentRouter 对照实现**（DashScope 兼容模式，复用现有 Key，LLM 自报置信非校准）；`IntentRouterConfig` 按 `app.intent.provider`（none/jev/qwen）选型，application-local.yml 已增量设 `provider: qwen` + `qwen.model: qwen-plus`（**qwen-turbo 在该账号被限流 429**）。实测 242 测试全绿；真实演示：闲聊→"闲聊问答 0.99/短路候选"，知识问题→"知识库问答 0.95/建议自动开检索"，延迟 1.1-1.3s。Jev 切换只改 provider=jev + 填 Key。

## 追加（2026-10-26）：批 1 生效——意图结论反哺开关

- 用户案例"查一下先维信息的员工年假有几天"暴露批 0 空转（意图 0.95 建议开检索但行为不变）。批 1 落地：`IntentDecision.applyTo()`（**只开不关**规则：用户已勾选=维持，未勾选命中=自动打开并在活动事件中写明"（原为关闭）"；短路候选/低置信维持）；`AiController.doChatWithManus` 的 classify **前移到检索/工具装配之前**，effectiveKnowledgeSearch/effectiveWebSearch 反哺 RAG 调用与 toolFactory；summary() 改为 `summary(action)` 携带实际动作；路由器日志改结构化字段（攒观察数据）。
- e2e（qwen 路由）：不勾知识库问年假 → "已自动开启知识库检索（原为关闭）" + rag-trace 出现 + **命中《员工手册（2024版）.pdf》并答出年假天数规则带引用**（批 0 同问题回答"无法确认"）；闲聊 → "维持用户开关"、检索未误开。243 测试全绿。
