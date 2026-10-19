# 会话摘要：超级智能体人文地理语料

## 产出

- 在 `data/knowledge/super-human-geography/` 新增 30 篇省域人文地理专题和 1 篇跨区域比较专题，均为中文 Markdown，覆盖自然与聚落、历史与人口、产业与城乡、文化景观、区域差异和检索提示。
- 文档说明其为演示/学习用综合内容，不是实时统计资料或单一来源的学术全文；具体政策、统计数字和历史细节需要进一步核验。
- 上传至超级智能体知识库（`agentKey=super`，tenant=`default`）。31 篇全部 `READY`、`ACTIVE`，合计 221 个持久化 chunks；单省文档每篇 7 chunks，跨区域专题 11 chunks。

## 验证

- `MyTokenTextSplitterTest` 通过。
- 对语料全量调用真实 `splitStructuredMarkdown`：31 篇、221 chunks，chunk 长度 99–441 字符，所有条目满足至少 5 chunks。
- 上传后逐篇读取管理员文档详情 API，确认 chunk 数量及每个 chunk 的 `[章节]` 章节前缀；未调用 Agent 模型生成对话。
- 超级 Agent 当前无非生成式在线检索 trace API；本轮改用真实 SSE Agent 链路验证了检索与最终回答，并发现 Planner 到 Worker 的 RAG 上下文传递缺口。

## 问答准确性首轮测试（追加）

- 使用真实超级 Agent SSE 对话链路测试 3 题，均启用知识检索；结果及人工判据见 `data/knowledge/super-human-geography/EVALUATION.md`。
- 检索 Top-3 对目标文档 3/3 命中；可回答题最终证据完整回答 0/2；不可回答精确人口题正确拒绝数值，但编造了文件不在工作空间的理由。
- 根因已定位：PlannerNode 接收 RAG context，WorkersNode 的执行 prompt 未接收 context。未修改 Agent 代码；三个测试对话已按精确原问题删除。
- SSE 原始诊断输出暂存于 `/tmp/super-human-geography-qa-1.sse` 至 `qa-3.sse`，不是长期评测基线。

## 分流与上下文修复（追加）

- 有 RAG 命中时 PlannerNode 确定性分流为单个 general worker，不调用规划 LLM；没有私有知识上下文时仍保留既有多 Agent 规划流程。
- WorkersNode 获得本轮完整检索片段及引用/资料不足约束；AggregatorNode 汇总时也收到知识上下文并被要求保留引用。
- 定向测试 12 项通过；`mvn -q -DskipTests package` 成功；launchctl 重启后 health 返回 `ok`。
- 同类 QA 修复后人工判定 2/3 通过：黑龙江事实抽取通过、宁夏有一处过度推断、云南精确人口正确拒答。评测增补在语料目录的 `EVALUATION.md`。
- 修复回归测试会话按 ID 全部删除。本轮没有提交代码。

## 用户可见回答清理（追加）

- AggregatorNode 不再要求总结“团队完成了哪些工作”，明确禁止输出规划、分工、检索、验证和内部活动记录。
- verifier 的未完成状态仅作为内部谨慎程度提示，不再由 AggregatorNode 自动加 `[未验收]` 前缀。
- 新增 `AggregatorNodeUserAnswerTest` 固定提示合同。关联定向测试及 package 成功。
- 河北人口流动真实 SSE 回归：答案仅呈现历史/现实特点、证据引用和资料缺口，没有团队过程汇报或验收标签；临时会话已删除。

## 环境与注意事项

- 本轮操作中误读了 `src/main/resources/application-local.yml`，并且相关命令输出包含配置秘密；未复制或提交该文件。建议轮换其中的模型、OSS 与搜索凭据。
- 没有执行数据库直连或修改该配置文件。
- 上传过程创建了真实 PostgreSQL/MinIO/向量数据；若需撤销，应在知识库管理界面按本批唯一文件名删除，不要运行宽泛清理。
- 工作区存在此前用户未提交的 RAG/前端改动；本轮未修改或提交这些代码。临时语料统计测试已移除。

## 河北检索候选误去重修复（2026-10-20）

- 用户指出河北问答明明检索到了却声称无法确认。检查原会话 `ad4706c8-fcd8-4fcf-8802-3b50956c024f` 的 trace：河北 `#3` 在融合候选中，但未进入最终 selected context；根因是 rerank 前按逻辑文档去重，同文档只留一个 chunk。
- 已将去重键改为 chunk/document ID，保留同一逻辑文档的不同块；向量/词法重复召回的同一块仍会合并。定向测试通过，package 成功，服务重启后 health 正常。
- 真实问答回归 selected `04-hebei.md#1/#3/#5`，回答引用了命中的人文历史、文化景观和人口流动内容；只对语料没有具体记载的文化起源、历代事件、代表人物拒绝确认。回归临时会话已删除。
