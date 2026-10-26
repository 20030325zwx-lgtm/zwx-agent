# 会话交接：超级智能体 token 计量（2026-10-26）

> 本份取代 `session-2026-10-26-aster-mcp-toolbox.md` 作为「最新一份摘要」；aster-mcp 那份内容仍然有效，其遗留事项（两个未 push 提交）已合并到本文第 3 节。

## 1. 改了什么

**目标**：A 档第①项——先计量再优化，用真实运行定位 token 大头。设计文档见 `docs/design/2026-10-26-token-usage-accounting.md`。

后端：

- `V25__add_agent_run_token_usage.sql`：`agent_run` 增 `prompt_tokens/completion_tokens/llm_calls/token_usage(jsonb)` + `(created_at, prompt_tokens)` 索引。
- 新增 `agent/observability/TokenUsageService`（@Service，按 runId 内存台账，`MAX_LEDGERS=200` 兜底，run 结束 `release`）。
- 新增 `agent/advisor/TokenUsageAdvisor`（`CallAdvisor + StreamAdvisor`，order=100）：从响应的 `Usage` 取真实 token，从请求侧分解出系统提示字数、工具 schema 字数与工具数、正文字数/条数、**工具结果字数**。整体 try/catch，计量失败绝不影响生成。
- 四个图节点接入：`PlannerNode/VerifierNode/AggregatorNode` 构造器加 `TokenUsageService` 参数，各自持 `AtomicReference<runId>`，`apply()` 首行从 `GraphState.RUN_ID` 写入；`WorkersNode` **删掉共享 ChatClient，改为每个子任务独立构造**，phase 名 `worker·调研员#1`。
- `ManusGraphOrchestrator`：`execute()` 里 `tokenUsage.bindContext(runId, knowledgeContext.length())`；成功与异常两条路径在 `persist()` 之后、`sendDone()` 之前调 `publishTokenUsage()`（落库 + 推 SSE `usage` 事件 + 释放台账）。
- `AgentRunService.recordTokenUsage()`：数值列 `COALESCE(x,0)+?` **累加**（续跑每一段都计入），`token_usage` 存最近一次分解。`AgentRun` 增 `tokenUsageJson`，`AgentRunView` 增 `tokenUsage` 并经 `GET /ai/manus/conversations/{c}/runs/{r}` 透出。

前端：

- `SuperAgent.vue`：`parseTokenUsage()` helper（字符串/对象都吃）；`normalizeRun` 增 `tokenUsage`；注册 SSE `usage` handler（自带 try/catch）；`refreshActiveRun` 合并时保留已有用量。
- `RunInspector.vue`：新增第 4 个标签页「用量」——总量 + 按 prompt token 降序的 phase 卡片（占比条、调用数、工具结果/ schema/正文/系统词字数）。

测试：`TokenUsageServiceTest`(4) + `TokenUsageAdvisorTest`(5) 纯单测；同步修掉因构造器变参加坏的 3 处既有测试（`AgentRunViewTest`×2、`PlannerNodeKnowledgeRoutingTest`、`VerifierRoutingTest`）。

**B1（计量之后立刻接着做的零风险削减）**：实测发现 89% 的字符主体本应被已有的工作记忆压缩管住，但那套机制从未生效。修了三条 bug，各配一份记录：

- `docs/bugs/bug-2026-10-26-token-usage-missing-tool-payloads.txt`：计量漏计工具载荷（已在本日口径纠偏中修好）。
- `docs/bugs/bug-2026-10-26-memory-compression-never-fires.txt`：**压缩永不触发**，两条原因叠加——① `MemoryCompressionHook.totalChars()` 只算 `getText()`，读不到 3 万字的工具结果，`context-budget: 24000` 永远够不着；② `ToolCallAgent.act()` 用 `setMessageList()` 整体替换历史，切断了 `BaseAgent.newRunContext()` 交给 hook 的活引用，即使压缩生效也写不回。**这是本轮最大的一笔：预算 24000 早已配好且 enabled=true，等于装了刹车没接皮带。** 修法：`totalChars()` 补计 `toolCalls.arguments` + `responses.responseData`，`act()` 改用 `replaceHistory()` 原地 clear+addAll、回写分支改 `history.set(size-1, …)`。
- `docs/bugs/bug-2026-10-26-after-tool-calls-fired-twice.txt`：`act()` 里 `fireAfterToolCalls` 触发两次（库证据：某 run 12 次 searchWeb → 24 条 tool 轨迹，每条恰好 2 次），删掉重复的那一处。
- 全仓核查：`setMessageList` 现只剩 `replaceHistory` 的空表兜底一处，其余历史写入均为原地 `add()`，活引用契约全仓成立。

## 2. 验证结果

- `mvn test`（16 项，含上述新旧用例）全绿；`mvn -DskipTests package` 出包 17:02；`launchctl kickstart -k` 重启后 `/api/health` = ok，Flyway `Successfully applied ... now at version v25`。
- 前端 `npm run build` 通过。**未能做浏览器可视化验证**：in-app browser 报 `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`（0×0 视口），点击类动作不可用；「用量」标签页仅验证到编译 + 数据结构对齐，实际渲染样式未确认。
- 三次真实运行（本机 admin 账号，DashScope qwen）跑通全链路，SSE `usage` 与 REST `tokenUsage` 均拿到数据。
- **口径纠偏**：首版只算 `message.getText()`，出现「worker `avgMessageChars=435` 但 `promptTokens≈10700/次」的矛盾——工具调用的 `arguments` 和 `ToolResponseMessage` 的 `responseData` 不在 `getText()` 里。补 `toolResultChars` 后自洽。
- **大头结论**（同题联网调研，9 次调用 / 62494 prompt token）：`worker·调研员#1` 占 59622（**95%**），单次调用约 37.8k 字符里**工具结果占 33.6k（89%）**，峰值 65k；verifier+aggregator+planner 合计仅 6.6%。另一侧：`general` 角色默认挂全量 14 个工具 = 6373 字 schema/次，调研员角色收敛到 3 个 = 1198 字/次（**5.3×**）。

B1 之后：

- `mvn test` 全量 **254 项通过**（0 失败 0 错误 1 跳过）；`MemoryCompressionHookTest` 5→7 例。
- 打包前发现 `target/classes` 残留 133 个 `"X N.class"` 陈旧副本（src 已无对应文件），按 `docs/bugs/bug-2026-10-25-stale-duplicate-classes-in-target.txt` 用 `mvn clean package -DskipTests` 出包，`launchctl kickstart -k` 后 `/api/health`=ok、`Started ZwxAgentApplication in 3.206s`、`pipeline 已装配到 AgentHooks，注册 hook 数：6`。
- 复测数据见上面第 3 节第 0 条与设计文档第 4 节。

## 3. 遗留事项

0. **B1 复测结论（先看这条）**：压缩机制确认修好——日志首次出现 `[memory] 工作记忆压缩完成`（一轮 3 次），
   worker 单次工具结果字数 33637 → 12708~21622、单次 prompt 9937 → 4622~6729。
   **但整轮成本未稳定下降**：同题 3 次重跑 75652 / 88583 / 47765（基线 62494），方差来自 worker 轮数（6→8/9/16）与返工，
   而非单次体积。且观察到能力损耗信号：某轮 4 条任务结果来源链接数为 0（基线 8 条），返工质检意见是"缺 Redis 诞生背景与许可证节点"。
   → 下一步不是继续削减，而是**先把压缩做成无损**（摘要保留全部 URL、recent-keep 保最近若干条原文）**并先建固定评测集**。
   数据表见 `docs/design/2026-10-26-token-usage-accounting.md` 第 4 节。

1. **A 档 ②'（新排序首位）**：worker 循环里工具结果的截断/摘要/滑窗——只保留最近 K 轮原文，更早压成结论。这是唯一能动 89% 的地方。
2. **A 档 ②**：`WorkerRole.GENERAL.allowedTools()==null` 即全量工具，改按需授权；被明令禁用工具的子任务不应再挂 schema（实测样本里就出现了「仅依据私有知识回答、不要用工具」却带 14 个工具的情况）。
3. **A 档 ③**：DashScope 显式上下文缓存（创建 125% 计费、命中约 10%）。前提是把 RAG 块移出公共前缀，否则前缀每轮在变、缓存打不中；且 spring-ai-alibaba 1.0.0.2 的 `DashScopeAiUsage` 没有 `cached_tokens` 字段，命中率需另想办法观测。
4. **正确性问题（未修，仅记录）**：Jev 在本机知识库只有地理文档的情况下，对「Redis vs Valkey」判出「需要知识库 0.80」并**自动打开了用户明确关闭的知识库检索**，导致整轮答非所问、且跳过了 planner。这是成本之外的独立议题，建议单独排期。
5. 两个仓库各有 1 个未 push 提交：aster-mcp `e45db3a`、yu-ai-agent `3d74027`；本次 token 计量改动**尚未提交**。push 前需先确认 GitHub 身份（钥匙串凭据属 `ZWXSND`，非 `20030325zwx-lgtm`）。
6. 本轮跑出来的测试 run 留在本机 dev 库（admin / `default` 租户）下，未清理：基线 3 条 + B1 复测 3 条（`40235035` / `a072b7d8` / `bade65a6`）。
7. **执行轨迹在超级智能体路径下完全没接线**：`agent_execution_event` 里今日所有 `agent_run` 的行数都是 0，
   而 `ExecutionTraceHook.recordEvent()` 的前置条件之一是 `context.runId() != null`——图执行路径没把 runId 放进 `AgentRunContext`
   （只有 `tenantId/agentKey/conversationId`）。因此工作台看不到 worker 的工具级轨迹；单 agent 路径（如 run 95ccf075）才写得到，
   也正是在那里暴露出 afterToolCalls 翻倍。属独立问题，未在本次修复范围内。

## 4. 环境注意事项

- 后端：launchd `com.zwx.yu-ai-agent.backend`（8123，`--spring.profiles.active=local`），日志 `/tmp/yu-ai-agent-backend.log`；前端 launchd `com.zwx.yu-ai-agent.frontend`（Vite 3000）。改后端必须 `mvn -DskipTests package` 重新出包再 `launchctl kickstart -k gui/$UID/...`，`compile` 不更新 jar。
- 构建前建议 `mvn clean`：`target/classes` 里那批 `"X N.class"` 陈旧副本会污染 `@SpringBootTest`（见 `docs/bugs/bug-2026-10-25-stale-duplicate-classes-in-target.txt`）。
- 本地 API 冒烟可用 README 记录的默认管理员 `admin/admin123`（`POST /api/auth/login` 拿 JWT）。**接口前缀是 `/api/ai/...`**，`AiController` 有类级 `@RequestMapping("/ai")`，漏掉会 404。
- `src/main/resources/application-local.yml` 含真实本地库连接与密钥：不得删除/覆盖/展示其值/入库。
