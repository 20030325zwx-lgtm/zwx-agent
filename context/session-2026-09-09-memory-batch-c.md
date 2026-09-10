# 会话摘要：2026-09-09 分层记忆（批次 C）

> 状态：已完成、已打包、后端已重启（health ok）、全量测试 128 个 0 失败（5 个 Error 为存量基线）、端到端验证通过、提取开关已恢复默认关。未提交 git。
> 实施 design/plans/2026-09-08-memory-skills-workspace.md §3.3 批次 C；至此该方案三批次（A 工作区 / B 技能 / C 记忆）全部落地。

## 改动内容

1. **V7 migration**（`V7__add_agent_memory_fact.sql`）：`agent_memory_fact` 表（tenant_id/agent_key/user_id 可空/fact/fact_hash CHAR(64)/source_conversation_id/created_at/last_seen_at）+ `uk_agent_memory_fact` 唯一索引（tenant+agent+fact_hash，冲突更新 last_seen_at 的 upsert 依据）+ `idx_agent_memory_scope` 查询索引。
2. **`memory/MemoryService`**（新增）：
   - `extractAsync(tenantId, agentKey, userId, conversationId, userPrompt, answer)`：单线程 daemon 池异步；LLM（qwen-plus，复用 dashscopeChatModel）抽取 0~3 条事实 JSON → 宽容解析（剥围栏/截取数组/兼容 {fact:...} 项/去重/截断 500 字符）→ `INSERT ... ON CONFLICT DO UPDATE SET last_seen_at`；开关 `app.agent.memory.extraction-enabled` **默认 false**；身份缺失静默跳过；所有异常 warn 不影响对话；
   - `factsFor(tenantId, agentKey, userPrompt, limit)`：最近 50 条内 `rankFacts` 静态纯函数排序（recency 基础分 + prompt 关键词命中 +100/条，CJK 二三元 + 拉丁词提取）；
   - hash = sha256(归一化 fact)（压缩空白）。
3. **`AgentRunContext` 扩展 + BaseAgent 一行插桩**：新增 `messageHistory` 字段（工作记忆**活引用**，builder 注入），`newRunContext()` 传入 `messageList`——这是压缩/注入 hook 能改写消息历史的通道；直接 step()（无 run 生命周期）时为 null，hook 判空跳过。除此之外核心执行类零改动。
4. **三个 hook**（自动进管线，现共 5 个）：
   - `MemoryInjectionHook`（order=50，beforeRun）：`factsFor` 命中则把「【长期记忆参考】+ 事实列表 + 【本次请求】」前缀替换进首条 UserMessage；无事实/无身份/首条非 UserMessage 零改动；
   - `MemoryExtractionHook`（order=150，afterRun）：取历史最后一条非空 AssistantMessage 作 answer，交 extractAsync（开关关时立即返回）；
   - `MemoryCompressionHook`（order=250，beforeThink，转换型）：messageList 总字符 > `context-budget:24000` 且条数 > `recent-keep:6` 时，压缩最旧块为一条摘要 UserMessage（保最近 6 条原样）；`adjustBlockEnd` 保证块边界不拆散「助手工具调用+工具响应」配对（被保留首条是 ToolResponse 或块尾是带 toolCalls 的助手消息时边界后移）；LLM 失败降级为直接丢弃最旧块；`summarize` 包私有可覆写便于单测。
5. **Love 链路接入**（preparation 链路不经 ReAct，不硬套 hook）：`doChatByStream` 拼 system prompt 时显式调 `buildMemoryContext`（factsFor top5，异常返回空串）；`doFinally` 落库后把本轮问答交 `extractAsync`（answer 非空时，带 actor.userId()）。
6. **application.yml**：`app.agent.memory.*`（compression-enabled: true / context-budget: 24000 / recent-keep: 6 / extraction-enabled: false）。
7. **测试 20 个**：`MemoryServiceTest` 10（JSON 解析容错/围栏/对象项/上限去重/归一化/sha256 稳定/关键词命中排前/recency 兜底/CJK+拉丁关键词）、`MemoryCompressionHookTest` 5（超预算压缩+最近 6 条原样/LLM 失败降级丢弃/预算内不触发/小块不动/工具配对不拆散）、`MemoryInjectionHookTest` 5（前缀格式/无事实不动/非 UserMessage 跳过/无身份跳过/null 历史跳过）。

## 验证结果（端到端，临时开 extraction-enabled=true）

- 全量 128 测试 0 失败；V7 迁移 success=t；启动日志注册 5 个 hook。
- **提取**：manus run（"记住：我最喜欢的数字是42…"）→ 异步抽取「用户最喜欢的数字是42」落库；love SSE（"记住：我养了一只叫煤球的黑猫"）→ 抽取落库且**带 user_id**。
- **注入（ReAct 链路）**：新 manus 会话 → 日志 `[memory] 注入 1 条长期事实到 run 首条消息（agent=super）`（两个 worker 都注入）。
- **注入（love 链路）**：全新 love 会话问"你知道我养了什么宠物吗" → 回答直接说出「你养了一只叫'煤球'的黑猫」（事实来自 system prompt 注入，非对话历史）。
- 提取有延迟：异步 LLM 调用约 30~60s，验证时落库查询别太急（本次曾误判为失效）。
- 收尾：extraction-enabled 恢复 false 并重新打包重启（health ok）；测试账号/会话/事实数据全部清理。

## 环境注意事项

- 压缩 hook 无法自然触发 e2e（需 messageList >24000 字符，manus worker 短任务到不了），行为由单测覆盖；设计验收"上下文恒 < 预算"实为尽力保证——recent-keep 6 条本身超预算时不再压缩（保留最近消息是硬约束）。
- 临时提权验证法沿用批次 B：register → psql UPDATE role='ADMIN' → 登录 → 用完删。
- `application-local.yml` 未触碰。

## 遗留事项

- plans/08 阶段 3 剩余：ToolGuardHook；StepMonitoringHook 迁移（阶段 2 遗留，需逐字节对照 SSE）；
- plans/07 第二批：hash 去重拦截 + publish/rollback/versions/retire 四个端点；
- plans/05 剩余：混合检索、RAG 评测集、run 持久化恢复；
- 事实查询目前是"最近 + 关键词"，未做向量语义检索（设计非目标，表结构已预留）；
- LoveApp.doChat（sync 无生产调用方）未接记忆，属遗留死代码候选。
