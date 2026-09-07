# 会话摘要：2026-09-07 知识库重排序 + 文档版本治理第一批

> 状态：已完成、已构建、后端已重启（health ok）、前端 build 通过。未提交 git（沿用仓库惯例等用户指示）。
> 覆盖两块工作：① 召回-重排两段式检索（design/plans/05 的 rerank 项）；② 知识库文档版本与有效性治理第一批（design/plans/07）。

## 一、召回-重排两段式检索

### 设计确认
- 用户选定方案：DashScope gte-rerank-v2 **HTTP 直调**（dashscope-sdk-java 2.18.2 实际解析版本不含 rerank 包，未升级 SDK）；两条 RAG 链路都接。
- 架构：向量召回池（放宽阈值 0.4、topK 15）→ rerank 精排取 top 3 → 注入上下文；rerank 失败/超时降级为按向量分过滤截断，**绝不阻断回答**。

### 改动内容
1. `rag/DashScopeRerankService`（新增）：RestClient POST `dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank`，模型 gte-rerank-v2；API Key 复用 `spring.ai.dashscope.api-key`；`app.rag.rerank.*` 配置（enabled/model/timeout-ms=1200）；单文档/未配置 key（含 `your-api-key` 占位符）/异常一律返回 empty 降级；文档截断 1200 字符、上限 20 条。
2. `rag/LoveRagService`：改 `recallAndRerank()`（`app.love.rag.recall-top-k:15`、`recall-similarity-threshold:0.4`、`timeout-ms` 800→2500）；`LoveRagTrace` 增加 `rerankModel` 字段（null=未重排，保留 9 参兼容构造器）；`LoveRetrievalCandidate` 增加 `rerankScore`。
3. `rag/AgentKnowledgeRagService`：同样接入（`app.agent.rag.*` 配置）。
4. `application.yml`：新增 `app.love.rag.recall-*`、`app.agent.rag.*`（top-k/similarity-threshold/timeout/recall-*）、`app.rag.rerank.*`。
5. 测试：`DashScopeRerankServiceTest`（解析/越界/坏 JSON/禁用降级）。

### 验证结果
- 单测 10 个全过；love SSE 对话端到端走通，trace/reference 事件正常。
- 注意：sync 接口（`/love_app/chat/sync`）**不接私有知识库**且不落 trace，验证必须走 SSE。
- 遗留：本地 love 内置库与私有库当时均为 0 向量，重排真实 HTTP 调用在版本治理验证时才间接触发（单候选时自动跳过，多候选路径已被单测覆盖）。

## 二、文档版本治理第一批（最小闭环 + 前端展示）

### 改动内容
1. **V6 migration**（`V6__add_knowledge_document_versioning.sql`）：`agent_knowledge_document` 加 11 列（logical_key/version_no/lifecycle_status/content_sha256/effective_from/effective_to/published_at/archived_at/supersedes_document_id/created_by/conflict_reason）+ 回填（logical_key=小写去扩展名；READY 存量→ACTIVE 并算版本号）+ 4 索引（含 `uk_agent_knowledge_active_version` **部分唯一索引保证单一 ACTIVE**）+ 存量向量元数据补齐（DO 块防御向量表不存在的新库）。
2. **`rag/LogicalKeys`**（新增）：文件名→logical_key 归一化（小写、去最后扩展名、非字母数字压成连字符、空回退 document）；**路径前缀一律剥掉**（multipart 平铺无目录概念，防客户端伪造路径劫持版本链）。
3. **`AgentKnowledgeDocumentService` 重构**：
   - `upload(tenantId, agentKey, file, createdBy)`：不再删同名旧版（版本链保留），logical_key + 版本号（MAX+1）+ SHA-256 + supersedes + created_by 落库，lifecycle_status='INDEXING'；
   - `indexDocument()`：切片元数据带 logicalKey/versionNo/lifecycleStatus；成功后 status='READY' 且 lifecycle='READY'，若原状态是 INDEXING 则**自动 publish**（兼容旧"上传即生效"）；失败时仅新版本（原 INDEXING）标 FAILED，ACTIVE 重建索引失败保持原状态不误下线；
   - `publish(documentId)`：TransactionTemplate 事务内 FOR UPDATE 锁版本链 → 校验 READY → 旧 ACTIVE→ARCHIVED（archived_at/effective_to）→ 目标→ACTIVE（published_at/effective_from）→ **同一事务内翻转向量切片元数据**（同一 Postgres 库，原子无脏窗口）；publish 失败整体回滚旧版保持生效。
4. **检索**（`AgentKnowledgeRagService.recall`）：filterExpression 追加 `lifecycleStatus == 'ACTIVE'`（**版本过滤先于 rerank**）+ 召回后防御过滤（metadata 非 ACTIVE 丢弃，防发布切换脏向量）+ 按 logicalKey 兜底去重（局部 Set，无并发竞态）→ 再 rerank → topK。上下文注入带 `（vN）` 标记。
5. **引用/trace**：`LoveKnowledgeReference` 增加 `logicalKey/versionNo`（5 参兼容构造器，Love 内置库传 null 不受影响）。
6. **API**：`AgentKnowledgeDocument` record 扩展（带 9 参兼容构造器）；listDocuments 返回版本字段，排序改为 logical_key + version_no DESC；上传接口加 createdBy（admin 用户名）。
7. **前端 KnowledgeAdmin.vue**：文档列表加版本徽章（vN）+ 生命周期徽章（生效中/待发布/已归档/失败/处理中/冲突，`lc-*` class 不与旧 status-* 冲突）。
8. **测试**：`LogicalKeysTest`、`AgentKnowledgeRagServiceVersionFilterTest`（stub VectorStore + 匿名子类 stub rerank：ACTIVE 过滤/去重/rerank 衔接/降级阈值）、`AgentKnowledgeDocumentServiceUploadTest` 适配新签名。

### 验证结果（端到端）
- V6 迁移成功（flyway_schema_history version=6 success=t）。首次迁移曾因 `metadata->>'versionNo' IS DISTINCT FROM d.version_no`（text vs integer）失败，已加 `::text` 修复；Postgres 事务性 DDL 回滚干净，无残留。
- 上传 `refund-policy.md` → v1 INDEXING→ACTIVE，向量元数据 ACTIVE。
- **同名**重传（新内容）→ v2 ACTIVE（supersedes=v1，published_at），v1→ARCHIVED（archived_at/effective_to），v1 向量元数据翻转 ARCHIVED。
- love SSE 问答：回答只引用 v2 内容（十五天退货，v1 的七天未泄漏），references 带 `logicalKey + versionNo:2`。
- 单测全过、`mvn -DskipTests package` 成功、前端 `npm run build` 通过。

## 遗留事项（第二批）
1. hash 去重拦截（content_sha256 已落库，重复上传返回 DUPLICATE 未做）；
2. publish / rollback / versions / retire 四个 API（publish 服务方法已就绪，只差端点）；
3. 冲突检测（CONFLICT 状态、来源矛盾提示）、人工发布模式、RAG 评测集；
4. `AgentKnowledgeDocumentService.indexDocument` 自动发布失败仅 log.warn（doc 停在 READY），管理员界面无重试入口（第二批 publish API 解决）。

## 环境注意事项
- 后端 launchctl `com.zwx.yu-ai-agent.backend` 重启正常；`/tmp/yu-ai-agent-backend.log` 查日志。
- 本地 maven 仓库在 `~/Desktop/maventool/maven-repository`（非 ~/.m2），查依赖 jar 用该路径。
- `application-local.yml` 未触碰；DashScope key 已配置，rerank 自动启用。
- 验证知识库链路：上传 `POST /api/ai/agent-knowledge/documents?agentKey=love`（multipart，`-F "file=@路径;filename=同名.md"` 可控制逻辑文档归属）；检索验证走 love SSE（sync 不接私有库）。
- 本地测试后 DB 留有 refund-policy v1(ARCHIVED)/v2(ACTIVE) 测试数据，如需清理走 DELETE API（会连向量+OSS 一起删）。

---

# 追加：Agent Hook 管线阶段 1（design/plans/08）

> 状态：已完成、已打包重启（health ok）、manus 图编排烟雾通过。全量测试 80 个中 5 个 Error 为**存量环境问题**（stash 验证改动前同样失败：LoveAppTest 4 个 DB FK、PgVectorVectorStoreConfigTest 1 个），与本改动无关。

## 改动内容

1. `agent/hook/` 新增五件套：`AgentRunContext`（每 run 一份 + attributes 属性袋）、`PlannedToolCall`（工具调用计划可变视图）、`HookAbortException`（拦截型唯一短路手段）、`AgentHook`（8 切点 + onFinish/onInterrupted/onError/afterRun，default 空实现）、`AgentHookPipeline`（@Component，容器收集按 order 排序、同 id 去重；观察型异常吞掉记 warn，HookAbortException 传播）。
2. 装配点：agent 实例是手工 new 的非 Spring Bean → `AgentHooks` 静态持有器，Pipeline @PostConstruct install；无 Spring（单测）时 pipeline()==null 全部 no-op。
3. `BaseAgent` 插桩：run()/runStream() 在 state=RUNNING 后建 ctx（`newRunContext` 可重写），fireBeforeRun；循环内 beforeStep/afterStep（ctx.currentStep 同步）；stopReason 分支 fireOnInterrupted；正常结束 fireOnFinish；catch 加 HookAbortException 专用受控分支（不发 generation-error，走 onInterrupted+摘要式输出）；finally fireAfterRun 并清 activeRunContext。
4. `ToolCallAgent` 插桩：step() 包裹 think（beforeThink/afterThink，think 本体未动）；act() 前 fireBeforeToolCalls（HookAbortException → 返回「工具调用被拦截：原因」），计划被修改则 `applyPlannedCalls` 重建 AssistantMessage/ChatResponse（未修改原样返回零开销）；工具执行后 fireAfterToolCalls。
5. 测试：`AgentHookPipelineTest`（6：顺序/去重/隔离/短路/计划修改/静态装配）、`ToolCallAgentHookTest`（2：计划未变原样返回/修改后重建保 id 与文本）。

## 设计要点（写在 plans/08，不再重复）

- SseEmitter 不交给 hook，事件仍由 BaseAgent 统一发。
- think 重试、completionHandler 落库、RAG 拼接、各 Tool 内沙箱**不迁**（理由见 plans/08 §4.2）。
- 阶段 2 待做：ExecutionTraceHook（manus/test/graph 补 trace）、StepMonitoringHook（activity+无进展检测迁出 runStream）；阶段 3：ToolGuardHook/技能注入/记忆压缩。

## 验证结果

- 新增 8 个单测全过；全量 80 个测试中 5 个 Error 与改动前完全一致（存量）。
- `mvn -DskipTests package` + launchctl 重启 health ok；日志出现 `[agent-hook] pipeline 已装配到 AgentHooks，注册 hook 数：0`。
- manus SSE 端到端（graph → GraphWorker 继承 ToolCallAgent）正常返回 plan/work activity 事件，插桩零行为变化确认。

## 环境注意事项

- 写新 hook 只需：实现 AgentHook 接口 + @Component 注解（id/order 必填），自动进管线；单测里 new AgentHookPipeline(List.of(hook)) 即可，不依赖 Spring。
