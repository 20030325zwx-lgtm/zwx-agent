# 方案 05：智能体架构演进、RAG 质量与长期记忆

> 本方案是"全能 agent"目标下的能力扩展规划，依赖方案 01/02 的安全地基。分三条线：RAG 质量（先止血再提质）、上下文与记忆、多智能体架构决策。

## 1. 目标

- 知识库回答稳定可预期：检索命中可解释、可评测、可回归。
- 上下文管理有 token 预算；用户级长期记忆支撑个性化。
- 智能体层从"单 ReAct 循环"演进为可编排的 planner-executor 结构，为多智能体协作留出接口。

## 2. 现状基线（审计证据）

### RAG
- 单路 pgvector cosine，topK=3、阈值 0.55；无混合检索、无 rerank；`QueryRewriter` 存在但主链路未接入 | `rag/LoveRagService.java:37-38`、`rag/AgentKnowledgeRagService.java:22-24`、`AiController.java:312-313`
- 800ms 预算内含 DashScope 远程 embedding；超时静默降级且 `available` 恒 true，前端无法区分 | `LoveRagService.java:37-74`
- 私有库检索链路无超时 | `AgentKnowledgeRagService.java:21-25`
- 文档无删除端点；重复上传生成新 UUID → 重复索引，旧版本污染检索 | `AgentKnowledgeDocumentService.java:49-50、158`
- 索引先删后插无事务，部分失败残留半份索引 | 同文件:93-98
- embedding 同步串行（每 100 chunk 一批，内部多次远程调用），限流即整任务 FAILED | `LoveKnowledgeIndexService.java:47-49`
- 无评测集，检索质量改动无法验证 | `doc/ROADMAP.md` RAG 模块

### 上下文与记忆
- 最近 20 条硬编码，拼字符串进 system prompt，与 RAG context、skill prompt 混装，无 token 预算 | `chatmemory/PostgresChatMemory.java:17`、`LoveApp.java:134-140`、`TravelPlannerApp.java:57-60`
- 双记忆链路：流式手拼 history vs 非流式 `MessageChatMemoryAdvisor`，语义不一致 | `LoveApp.java:97-108 vs 134-140`
- 无用户级长期记忆（画像/偏好/事实提取）

### 智能体架构
- `BaseAgent → ReActAgent → ToolCallAgent → ZwxManus` 手写单智能体 ReAct 循环，每请求 `new` 实例；`think()` 失败返回上一轮陈旧回答、无重试 | `agent/ToolCallAgent.java:60-66、129-133`
- 无任务分解/计划/验证的显式建模；中断不可恢复 | `doc/ROADMAP.md` 智能体编排模块

## 3. 方案设计

### 线 A：RAG 止血（1-2 周）

1. **文档生命周期**
   - 上传幂等：`agent_knowledge_document` 按 `(tenant_id, agent_key, filename)` 唯一；重复上传 = 覆盖旧版本（同事务删旧向量、写新向量）。
   - 新增 `DELETE /agent-knowledge/documents/{id}`：删文档记录 + 按文档 id 删全部向量。
   - 重建索引事务化：新向量先写（带新版本号），成功后原子切换版本标识、删旧版本；失败不留半份。
2. **检索超时与可观测**
   - embedding 与向量查询分开预算（如 embedding 600ms + 查询 400ms）；私有库链路补齐超时保护。
   - 降级显式化：`LoveRagTrace` 增加 `degraded=true + reason`，前端据此展示"本次回答未使用知识库"。
   - 检索迁入 `ragExecutor`（方案 04），不再占 commonPool。
3. **QueryRewriter 接入主链路**：检索前对口语化/指代性问题改写（结合最近 3 条历史），trace 记录原始 query 与改写后 query。

### 线 B：RAG 提质（评测驱动）

1. **评测集先行**：`src/test/resources/rag-eval/` 建 YAML 评测集（问题、期望命中的 chunk 标识、标准答案要点）；写离线评测器输出 recall@k / MRR / 引用正确率。任何检索改动先跑评测。
2. **混合检索**：PGVector 之外启用 PostgreSQL `tsvector` 全文索引（中文用 `zhparser` 或先退化为 jieba 预分词入库），RRF（Reciprocal Rank Fusion）融合两路结果。
3. **重排序**：融合后取 top 20，调 DashScope `gte-rerank` 取 top 3；rerank 失败降级为融合序（降级记入 trace）。
4. **动态阈值**：topK 与相似度阈值按智能体配置化（love 偏对话、travel 偏事实），不再全局 0.55。

### 线 C：上下文与记忆

1. **Token 预算器**：引入 token 估算（qwen tokenizer 或字符近似），上下文装配改为预算制：system prompt（固定）→ RAG context（≤2k token）→ 长期记忆（≤500 token）→ 最近历史（剩余预算内按条数与单条长度截断）。替换"固定 20 条"。
2. **统一记忆链路**：流式与非流式共用同一 `ContextAssembler`，删除手拼字符串与 `MessageChatMemoryAdvisor` 并存的局面。
3. **长期记忆（用户级）**：
   - 表 `user_memory`（user_id, kind[FACT/PREFERENCE/PROFILE], content, source_message_id, confidence, status[ACTIVE/EXPIRED/EDITED], created_at, updated_at）。
   - 写入：每轮结束后异步用小模型（qwen-turbo）从"用户消息+最终回答"提取候选事实，去重合并（同主题覆盖更新）。
   - 读取：每次对话开始按 user_id 检索 ACTIVE 记忆注入预算区。
   - 用户可控：前端提供记忆列表页（查看/编辑/删除/停用）——这是与"可解释、可遗忘"的 ROADMAP 项对齐的最小实现。
   - 隐私默认：情感咨询等敏感智能体可配置关闭记忆提取。

### 线 D：智能体架构演进

决策点：**不引入重量级 multi-agent 框架（LangGraph/AutoGen/CrewAI），在现有 BaseAgent 体系上演进**，理由：现有单智能体链路已深度绑定 Spring AI 工具调用与会话持久化，推倒重来成本高；先补状态机显式化，编排需求明确后再评估 Spring AI Alibaba Graph。

1. **修复执行正确性**（先行，独立小改动）：
   - `ToolCallAgent.think()` 失败：区分"模型无工具调用（正常结束）"与"调用异常"；异常时重试 2 次（指数退避），仍失败则以显式错误事件结束（状态 `FAILED`），**不得返回上一轮残留回答**；错误文案不写入记忆。
   - `ReActAgent.step()` 的 `e.printStackTrace()` 改为日志。
2. **显式任务状态机**：`Plan → Execute → Verify → Deliver` 四阶段建模（`agent/model/` 下新增阶段枚举与转移校验），plan 阶段输出结构化计划（JSON：步骤、所需工具、完成判据），execute 逐步推进，verify 校验完成判据（简单版：模型自检 + 工具结果非空），deliver 输出总结。现有 20 步 ReAct 循环作为 Execute 阶段的实现保留。
3. **运行持久化与恢复**：agent run 落库（run 状态、计划、已完成步骤），中断后可从最后完成步骤恢复（依赖方案 03 的取消语义与 04 的会话互斥）。
4. **多智能体（二期评估）**：在状态机之上引入 sub-agent 抽象（`DelegateTool`：把子任务委托给带受限工具集的子 BaseAgent 实例，结果回传主 agent），这是成本最低的多智能体形态；是否引入路由型多智能体，待 sub-agent 实际任务成功率数据决定。

## 4. 交付拆分

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| 批次 1 | RAG 止血（生命周期/超时/降级显式/QueryRewriter） | 重复上传不再重复索引；降级可见；评测集基线建立 |
| 批次 2 | 混合检索 + rerank | 评测集 recall@3 / MRR 相比基线提升且记录数值 |
| 批次 3 | Token 预算 + 统一 ContextAssembler | 长会话上下文不超限；两链路行为一致 |
| 批次 4 | 长期记忆最小闭环 | 提取/注入/列表页/删除全流程 |
| 批次 5 | think 修复 + 四阶段状态机 + run 持久化 | 现有终止测试全绿；中断 run 可恢复 |

## 5. 风险与决策点

- rerank/改写每轮增加 1-2 次模型调用：预算与延迟需在评测时一并测量（目标端到端首 token 延迟增加 <800ms）。
- 长期记忆的提取质量是双刃剑：先做"只存显式偏好类事实 + 用户可编辑"，避免激进画像。
- 中文全文检索组件（zhparser）在容器镜像中的可用性需要验证；不可用则退化为预分词方案。
- 状态机改造是 `BaseAgent` 体系最大的一次重构，必须在方案 04 线程模型完成之后进行，并用现有 `BaseAgentTerminationTest` 作为回归基线补充用例。
