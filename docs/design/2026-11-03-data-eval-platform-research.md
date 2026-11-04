# 数据中台 + 评测中台调研（2026-11-03）

状态：调研结论；**E1 评测中台 MVP 已于 2026-11-04 实施**（见 `docs/context/session-2026-11-04-eval-platform-e1.md`，V23 迁移 + platform.eval 包 + /ai/eval API + 前端评测台，四类评估器隔离 E2E 全通过）。
关联：`docs/design/2026-10-31-product-direction-progress-assessment.md`（整体进度评估）、`docs/design/2026-10-28-permission-modes-and-eval-trajectory.md`（评测可复现性）、`docs/design/2026-10-26-token-usage-accounting.md`（token 计量）。

## 1. 问题定义：两个中台和智能体平台是什么关系

用户判断：光有智能体平台不够，还需要一个**不断供数的数据中台**和一个**评测中台**。行业参考印证了这个判断——Agent 平台只是执行引擎，缺了两侧支撑时：

- 没有评测：每次改 prompt / 换模型 / 改工具集都是盲改，无法回答「通过率变化是代码变了还是模型变了」（这正是 10-28 那轮已经写下的痛点）。
- 没有数据回流：用户真实使用产生的 BadCase、偏好、语料全部散失，知识库和智能体质量只能靠人工堆料，没有飞轮。

三者的闭环关系：

```
        ┌────────────── 数据中台 ──────────────┐
        │  接入 → 加工 → 资产（语料/评测集/偏好）→ 服务 │
        └──── ↑回流（反馈/轨迹/BadCase）   ↓供数 ────┘
   用户使用 → 智能体平台（执行） ← 评测中台（回归门禁/实验对比）
```

- **数据中台**对 Agent 平台供四类数：RAG 语料（已有雏形）、评测集（golden set）、偏好/标注数据（来自用户反馈）、素材资产（创作模块已有）。
- **评测中台**消费评测集 + 被测对象（模型/prompt/agent 配置/工作流），产出实验结果与 BadCase，BadCase 打标后回流成新评测集。

## 2. 现状盘点：项目里已经有什么（比预期多）

按源码核查，观测和评测的「最难部分」其实已有雏形：

| 能力 | 现有实现 | 缺口 |
| --- | --- | --- |
| 模型 IO 轨迹 | `agent_llm_call` 表（run/phase/model/token/延迟/请求响应摘要，全量可开关） | 无查询 UI，无按场景聚合分析 |
| 成本观测 | `agent_run` token 台账（prompt/completion/llm_calls + token_usage jsonb 分解），SSE + 历史回看 | 无跨 run 趋势与成本看板 |
| 工具与执行证据 | `agent_tool_call`、`agent_tool_confirmation`、`agent_execution_event` | 散落，无统一证据查询 |
| 轨迹导出 | `GET /ai/manus/conversations/{cid}/runs/{rid}/trajectory`（聚合 run 元数据/检查点/工具账本/模型调用/事件） | 已是回流评测集的理想数据源，但目前只服务失败留证 |
| 评测脚本 | `evaluation/eval-sets/`：`run_task_eval.py`（隔离 eval-bot 账号、YOLO 模式、manifest 记录 git commit + 评测集 SHA-256 + 模型配置快照）、`score_task_result.py`（独立评分可重放）、`run_baseline.py`、`run_intent_gate.py` | 全部是本地 Python 脚本，结果只是 JSON 文件，不入库、不可查、无趋势 |
| 评测资产 | `knowledge-intent-eval-v1.json`、三个 baseline JSON | 评测集以临时 JSON 存在，无版本、无字段 schema、无配比管理 |
| 数据资产 | 知识库（文档/分块/pgvector/审核流 `agent_reviewer` + `agent_knowledge_review_log`）、`agent_memory_fact`、创作素材库、媒体任务用量记录 | 知识库就是「语料中台」雏形，但没有向评测/微调供数的契约 |
| 用户反馈 | 无（前端 `RunInspector.vue` 的 feedback 只是 verifier 意见文本） | **完全空白**：没有任何点赞/点踩/评分入口，回流层断了 |

结论：不需要从零建。缺的是「资产化（入库、版本、可查）+ 回流（反馈/BadCase）+ 自动化（评测作为平台任务跑）」三层。

## 3. 行业参考

### 3.1 评测中台的主流能力模型

综合 Langfuse / promptfoo / DeepEval / RAGAS / 华为云 AgentArts / MLflow 的公开实践，评测中台的核心对象是：

1. **评测集（Dataset，版本化）**——AgentArts 的设计最有参考价值：
   - 表头（字段）按评估器**反向设计**：RAG 类需要 `input / reference_output / context`；创作类只要 `input`（含约束）；对话类只要 `input`。绝不预填 `actual_output`（那是运行时抓的）。
   - 规模配比建议（30~50 条起步）：正向 ~50%、边界 ~20%、对抗/拒答 ~20%、安全 ~10%；RAG 场景约 20% 拒答题。
   - 评测集要「发布版本」才能被实验引用（不可变快照）。
2. **评估器（Evaluator）**四类：规则断言（免费、确定）、LLM-as-judge（通用质量）、轨迹评测（工具选择/顺序/任务完成度，agent 专属）、人工评审（真值兜底）。
3. **实验（Experiment）**= 评测集版本 × 被测对象（模型/prompt/配置）× 环境快照（commit、模型配置）。本项目 manifest 快照设计与此一致。
4. **在线评测**：对生产 trace 抽样跑 LLM-as-judge、聚合用户反馈。
5. **BadCase 闭环**：打标签（如「知识库缺失 / 提示词弱 / 工具提参错误」）→ 聚类 → 一键转成评测集用例 → 作为回归门禁。

开源选型速览：

| 工具 | 定位 | 对本项目的适配度 |
| --- | --- | --- |
| Langfuse | 自托管观测+评测一体（trace、datasets、experiments、annotation queue、LLM-as-judge） | 功能最全，但 Java 集成弱：官方 Java API client（2025-03）之外只有社区 Spring Boot starter 和 OTel 路线；自托管需额外 ClickHouse/Redis 等容器；会与 `agent_llm_call` 形成双份数据 |
| promptfoo | CLI 驱动的 prompt/RAG 测试与红队 | 适合 CI 门禁场景，Node 生态 |
| DeepEval | pytest 风格评测框架，含轨迹评测 | Python 库，非平台 |
| RAGAS | RAG 专项指标（faithfulness 等） | 可作为评估器算法参考 |
| MLflow | 实验追踪+LLM 评测 | 偏训练实验管理，重 |
| AgentEvals (LangChain) | judge 库（轨迹匹配等） | 算法参考 |

### 3.2 数据中台在 AI 时代的重构

- 传统数据中台面向 BI；AI 时代的范式（AWS 称 AIDLC，国家数据局 2025 高质量数据集建设指引）把**数据资产直接服务模型/智能体**：数据集成为一等公民，强调质量标注、版本、血缘。
- 华为云 AgentArts 的「从 Trace 回流数据集」是智能体场景数据中台的最小闭环范式：观测（Trace）→ 提取真实问答 → 沉淀为评测集 → 评测 → 人工改分/打标 → 迭代评测集。
- AIDD 社区的做法：离线评测（回归门禁）+ 在线评测（生产抽检）双轨，Good/BadCase 自动回流评测集。
- 对本项目的含义：**数据中台 ≠ 再建一个大數据平台**，而是围绕「语料/评测集/偏好数据」三类 AI 资产建采集-加工-资产-回流-服务五层，多数基础设施（PostgreSQL、MinIO、docling 解析、pgvector、知识审核流）已经就位。

## 4. 路线对比与推荐

### 路线 A：自托管 Langfuse 承担观测+评测

- 优点：现成 UI（trace 浏览、数据集、实验、标注队列）、社区活跃、省大量前端。
- 缺点：新增一套自托管栈（服务器是 1 核小机 + 已有 compose，负担不小）；Java 侧集成要靠 OTel 或社区 starter，成熟度一般；`agent_llm_call` 与 Langfuse trace 双写双存；评测要贴合本项目业务指标（意图路由、小说一致性、海报质检、成本），绕不开自研评估器，最终 Langfuse 只当 trace 查看器用，性价比低。

### 路线 B（推荐）：在现有 Spring Boot 单体内自建轻量中台

理由：

1. 观测主干的 80% 已完成（轨迹表 + 导出 API + token 台账），自建的边际成本低。
2. 技术栈零新增：PostgreSQL（jsonb 列足够灵活）、既有异步 job 模式（media job / workflow run 已验证）、既有权限/租户体系。
3. 评测中台的核心价值在本项目的业务评估器（意图门禁、拒答、创作质检、成本预算），现成工具都覆盖不了。
4. 产品定位是个人/小团队工作台，一体化体验比独立平台更重要。

Langfuse 降级为可选：以后若需要深度 trace 分析 UI，再按需引入，不阻塞主路线。

### 架构草案（路线 B）

```
智能体平台（现有）
 ├─ 轨迹/用量/工具账本（已有表）──→ 回流 ──→ ┌ 数据中台 ┐
 ├─ 用户反馈（新增：会话/回答/创作产物 点赞点踩+理由）──→ │ BadCase 库    │
 └─ 知识库/素材库（已有）──────────────→ │ 语料/素材资产 │
                                        │ 评测集(版本化) │
                                        └──────┬───────┘
                                          供数 ↓
                                        ┌ 评测中台 ┐
                                        │ 评估器(规则/LLM-judge/轨迹/人工) │
                                        │ 实验(数据集版本×被测对象×快照)   │
                                        │ 结果看板 / 回归门禁 / 在线抽检   │
                                        └──────┬───────┘
                                     BadCase 打标 → 回流评测集
```

## 5. 落地路线图（建议 MVP 顺序）

### 阶段 E1：评测中台 MVP（先建，因为它给数据回流提供「归宿」）

- 表：`eval_dataset`（名称/场景/评估器类型）/ `eval_dataset_version`（发布快照）/ `eval_case`（jsonb 字段按场景扩展：input、reference_output、context、tags、来源 badcase_id）/ `eval_experiment`（被测对象快照：commit、模型配置、prompt 版本）/ `eval_case_result`（得分、judge 摘要、cost、trajectory 引用）。
- 评估器框架：接口 `Evaluator { score(case, actual, trajectory) }`，首批实现 ① 规则断言（含/不含关键词、格式、链接数）② LLM-as-judge（复用现有 Provider/Model 体系）③ 意图路由正确性（把 `run_intent_gate.py` 的逻辑搬进来）。
- 执行：评测跑成后端异步任务（复用 media job 的执行/重试/事件模式），用隔离 eval 账号 + YOLO 快照（沿用 10-28 设计）。
- 结果：SSE 进度 + 结果页（通过率、得分分布、每例轨迹点开、成本合计）；历史趋势按实验对比。
- 迁移：把 `evaluation/eval-sets/*.json` 三个 baseline + intent gate 导入为 v1 数据集。

### 阶段 D1：数据中台 MVP（回流层，接住真实使用）

- 用户反馈：会话回答与创作产物增加 👍/👎 + 可选理由（一句话/标签），落 `user_feedback` 表，关联 run_id / project / asset。
- BadCase 管理：负面反馈 + 评测失败用例进入 BadCase 库，打标签（知识库缺失/提示词弱/工具提参错误/拒答失败…），状态流转（待处理→已转评测集→已忽略）。
- 一键回流：BadCase → 评测集用例（自动带 trajectory 摘要进 context 字段）；Trajectory API 导出的 run → 评测用例。
- 语料治理看板：知识库规模/审核积压/检索命中率（检索命中已在 token 台账侧面可见，可后补埋点）。

### 阶段 E2/D2（后置）

- 在线评测：生产 run 抽样跑 LLM-as-judge；反馈聚合周报。
- prompt/模型实验管理：同一数据集版本上对比多个被测对象，产出选择依据。
- 供数契约：为未来 SFT/微调导出偏好对（feedback + 轨迹），为 RAG 优化导出「检索命中-答案质量」关联数据。
- 视需引入 Langfuse（仅 trace 深查）或复用 RAGAS 指标作为评估器实现参考。

## 6. 风险与注意

- **评测账号污染**：10-28 已用隔离 eval-bot 账号解决，平台化后仍必须强制隔离，防止污染真实记忆/会话。
- **双 worker**：本地开发后端与服务器后端共用一个库（见 0.2.0 部署摘要），评测任务化后会两边抢跑；上线评测中台前应先解决（本地禁用调度或独立库）。
- **LLM-as-judge 成本**：每例一次 judge 调用，先只对失败样本+抽样通过样本跑 judge，规则断言先行。
- **不要为了中台而中台**：10-31 进度评估明确当前首要门禁是「可靠性收尾 + 一个主场景可交付」；评测/数据 MVP 的定位是支撑门禁（回归通过率、合格率、每件合格成本），不做成独立大平台。
- 反馈入口要轻（一次点击 + 可跳过理由），否则没人反馈，回流层名存实亡。

## 7. 专家方案对照与融合（2026-11-03 追加）

同日收到一份外部专家规划方案，已存档：[docs/design/plans/2026-11-03-data-eval-platform-expert-plan.md](plans/2026-11-03-data-eval-platform-expert-plan.md)。其整体框架（数据飞轮、闭环优先、评测先行、BadCase 回流、质量-成本-延迟三角）与本调研 §1/§5 的结论一致，不重复；下面只记差异与融合决定。

### 7.1 专家方案新增的硬通货（本调研未强调，采纳）

| 专家观点 | 对本项目的落地映射 |
| --- | --- |
| **版本四元组必填**：`模型 + Prompt + 知识库 + 工作流` 版本贯穿所有日志与评测报告 | `run_id` 即 trace_id 已贯穿；kb 版本已有（`agent_knowledge_document.version_no` + active 版本）；workflow 版本已有（`workflow_definition_version`）；模型快照只存在于评测 manifest，`agent_run` 需补 run 级 binding 快照列；**Prompt 版本化完全缺失，是唯一硬缺口**，需要轻量 Prompt 资产库（id@version + 效果数据关联） |
| **隐式信号四件套**：采纳率、编辑距离占比、重生成率、导出率——主观创作质量最便宜的真值，且用于交叉验证 LLM Judge（长期不相关 = rubric 或 Judge 失准） | 创作模块天然具备载体：`creative_draft` / `creative_asset` / 重生成请求。D1 埋点设计改为「一次埋齐四件套 + 👍👎」，而不是只做反馈按钮 |
| **单次采纳成本** 优于单次请求成本 | `media_usage_record` 已记成本，补「采纳」关联即可得；写进指标体系 |
| **Judge 校准**：固定 Judge 版本、定期核对 Judge 与人工评分一致性 | 进 E2；评测体系自身也纳入可信度指标（Judge-人工一致性、评测集覆盖度/新鲜度） |
| **第三方模型静默漂移检测**：固定评测集定时回跑 | 对本项目尤其对症（全调第三方模型），进 E2；复用评测任务化基建即可 |
| **评测集防污染**：评测集与 Prompt 示例/训练数据物理隔离 | 隔离 eval-bot 账号已有；补一条约定：评测集表只允许从 BadCase/Trajectory 回流和人工录入两个入口写入 |
| **统一质量定义**：同一套 rubric 既评生成质量也评数据质量 | 采纳为设计约定，避免两套标准 |
| **发布门禁思想**：变更必跑回归，不达标不上线 | 单人项目不做自动门禁流水线，轻量化为「部署前回归检查清单 + 一日对比报告」验收标准 |

### 7.2 需按项目现实裁剪的部分

- 专家假设大厂组织（数据工程/算法/标注人力、数据湖、A/B 分桶、语义缓存）。本项目为单人 + 小服务器：**数据湖 → PostgreSQL + MinIO 即是**；标注工具 → 复用知识审核流（`agent_reviewer`）；A/B 分桶、语义缓存 → 后置（token 台账已证明大头是工具结果整段重发，先做无损压缩而非缓存）。
- 专家路线图三阶段约 5~6 个月 → 压缩为 E1/D1 两步，阶段一验收直接采用专家口径：「任一 Prompt/模型变更，一日内拿到对比报告」。
- 评测集规模：专家建议 100~300 条/场景，按 AgentArts 实践先 30~50 条/场景起步，够支撑对比即可。

### 7.3 专家六个待确认事项的建议回答

1. **首批样板场景**：① 超级智能体任务执行（已有 `run_task_eval.py` + 隔离账号 + YOLO）；② RAG 知识问答（`knowledge-intent-eval-v1.json` 雏形）。创作（海报/小说）第二梯队，专家六维 rubric（相关性/事实性/风格一致/合规安全/可用性/表达质量）届时直接适用。
2. **模态**：文本先行；图文一致性/美学评分等多模态评测后置到创作场景进评测时再引入。
3. **数据边界**：本地自用平台风险低，仍按「合规前置」原则在日志入湖时带授权/脱敏标记（字段级，成本极低）。
4. **人力**：单人，优先工具自动化，不做需要专人运营的能力（标注队列、A/B 运营）。
5. **微调计划**：暂无。偏好数据（采纳/拒绝对、编辑 diff）**先存后用**——存储成本一个 jsonb 列，SFT/DPO 工厂确认有微调计划后再启动。
6. **可复用设施**：PostgreSQL/pgvector/MinIO/docling/RAG/知识审核流/`agent_llm_call`/trajectory API/media job 异步模式，全部复用，不新增中间件。

### 7.4 融合后的路线图（修订 §5，取代原文顺序）

- **E1 评测中台 MVP**（=专家阶段一）：补 `agent_run` 模型快照与 prompt 版本引用 → 评测集/版本/用例/实验/结果五张表 → 评估器（规则断言 + LLM-as-Judge 带 rubric 锚点）→ 实验对比报告 → 迁移 3 个 baseline JSON。验收：**任一 Prompt/模型变更，一日内出对比报告**。
- **D1 数据中台 MVP**（=专家 P0 埋点 + 回流）：行为埋点一次埋齐（👍👎 + 采纳/编辑 diff/重生成/导出）→ 生成日志补 versions 四元组与 feedback 字段 → BadCase 池（打标/状态）→ 一键回流评测集。验收：BadCase 从产生到进评测集 ≤ 1 分钟人工操作。
- **E2/D2**（=专家阶段二轻量版）：Judge 校准、固定评测集定时回跑（漂移检测）、单次采纳成本看板、Prompt 资产库、偏好数据沉淀、部署前回归清单。
- **后置**：A/B 分桶、语义缓存、红队评测集、多模态评测、SFT/DPO 数据工厂。

### 7.5 存储与部署形态（已确认）

- **共用同一个 PostgreSQL 实例，不新增数据库服务**；两个中台的表放独立 schema（`platform`），主库业务表留在 `public` 不动。理由：评测中台要直读主库 run/轨迹/知识库，同实例同 JVM 最短路径；评测表迭代快，隔离在独立 schema 不污染主迁移链；append-heavy 大表（`agent_llm_call`、评测产物）有明确归档边界。实现先保持一条 Flyway 链（迁移内 `CREATE SCHEMA platform` + schema 限定 DDL），表多再拆双链。
- **跨 schema 只存 ID 不加外键**（`run_id` 等由应用层保证一致性），保留将来拆库自由。
- **重物进 MinIO**：trajectory 原始 JSON、评测原始产物、完整 prompt 快照存 MinIO，库内只存得分、摘要与对象引用。
- **前置项：双 worker 抢任务**。本地开发后端与服务器后端共用一个库（见 0.2.0 部署摘要），评测任务化前必须解决：本地禁用调度或配置独立库。

## 8. 参考来源

- 华为云 AgentArts 评测集设计实践（trace 回流、字段反向设计、配比）：https://support.huaweicloud.com/bestpractice-agentarts/agentarts_06_0090.html
- Langfuse（观测+评测一体、自托管）：https://langfuse.com/self-hosting 、https://langfuse.com/resources/ai-agent-evaluation 、官方 Java client：https://langfuse.com/changelog/2025-03-03-langfuse-java-client
- Spring AI + Langfuse（OTel 路线）：https://langfuse.com/integrations/frameworks/spring-ai
- promptfoo：https://www.promptfoo.dev/docs/intro
- DeepEval 轨迹评测：https://deepeval.com/docs/evaluation-trajectory-based-llm-evals
- AgentEvals（LangChain judge 库）：https://github.com/langchain-ai/agentevals
- MLflow LLM 评测：https://mlflow.org/llm-evaluation
- AWS AIDLC 范式（数据中台 → AI 驱动数据生命周期）：https://aws.amazon.com/cn/blogs/china/ai-engineering-platform-aidlc-migration
- 国家数据局高质量数据集建设指引：https://www.nda.gov.cn/sjj/ywpd/sjzg/0922/ff808081-96b466bd-0199-6f6bd7d3-1bad.pdf
- 自托管评测工具横评（2026）：https://www.braintrust.dev/articles/best-self-hosted-ai-evals-tools-2026
