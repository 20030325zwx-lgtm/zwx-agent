# 数据中台 / 评测中台 建设细节设计（2026-11-04）

状态：**E2-1 / E2-2 / D2 主体已实施（2026-11-04）**，见文末「实施记录」。
上游依据 [2026-11-03 调研](2026-11-03-data-eval-platform-research.md) §7.4 路线图；
已交付部分见 `docs/context/session-2026-11-04-eval-platform-e1.md` 与 0.4.1 部署记录。

## 0. 一句话诊断

两个中台目前是**「跑得通的骨架」**，不是「能用的工具」。三处空心：

1. **评测的被测对象不可选**——实验只能测「当前线上配置」，无法回答「换 qwen-max 会不会更好」，与「实验 = 数据集版本 × 被测对象」的核心定义不符。
2. **数据入口只有一只手**——只有 chat 的 👍/👎（无理由、无标签、无创作产物），隐式信号为零，BadCase 没有根因，回流只有唯一去向。
3. **闭环的回路是断的**——评测结果不回到数据中台（无反馈→结果关联）、trace 浏览缺失、没有趋势与门禁，评测跑完就躺在那。

下面按对象逐个收敛。标注 **[P0]** 的是「不补就名不副实」，**[P1]** 是「用起来顺手」，**[P2]** 是「以后再要」。

---

## 1. 评测中台（eval-service）

### 1.1 被测对象（target）一等公民 **[P0]**

**现状**：`EvalExperimentService.create()` 里 candidate 是硬编码的——
`intentProvider=backend-internal`、`evalModel` 取服务端 `env EVAL_MODEL`、agent 固定 internal-api。
用户创建实验只能传 datasetId/versionNo/notes，**没有任何「测什么」的表达能力**。

**决策**：实验创建请求增加 `target`，服务端校验后合并进 candidate（表结构不动，candidate 本就是被测对象快照）。

```json
POST /api/ai/eval/experiments
{
  "datasetId": "...", "versionNo": 2,
  "target": {
    "kind": "live",                                    // live | model | prompt
    "model":  { "provider": "dashscope", "modelName": "qwen-max" },
    "prompt": { "assetKey": "task-planner", "versionNo": 3 },   // 可选
    "agent":  { "agentKey": "default" }                          // INTENT/TASK
  },
  "concurrency": 1,
  "budget": { "maxCases": 50, "maxCost": 2.0, "judgeSampleRate": 0.2 }
}
```

约束（服务端强校验，避免「看起来能测实则测不到」）：

| evalType | 允许的 kind | 说明 |
| --- | --- | --- |
| INTENT / RETRIEVAL / TASK | 只能 `live` | 执行在主站，服务端无法替换模型/prompt；`model`/`prompt` 传了直接 400 |
| PROMPT | `model` / `prompt` | 直连 DashScope，模型可换；`prompt` 指定 Prompt 资产版本 |

**验收**：同一数据集版本建两个实验（qwen-plus vs qwen-max），compare 能看到逐例 delta 与 judge 均分差。

**为什么先做这个**：现在这套东西的「实验对比」在语义上是假的——两个实验如果配置相同，对比结果只是两次采样的抖动。

### 1.2 Prompt 资产库（轻量） **[P0]**

**现状**：全系统唯一的硬缺口（调研 §7.1 已点出）。`agent_run` 没有 prompt 版本，PROMPT 评估器测的是用例里内联的 `system` 字符串，**与线上跑的不是同一段文本**，所以「任一 Prompt 变更，一日内出对比报告」这条验收标准目前做不到。

**最小实现**（不做模板渲染引擎，只做「可寻址的版本」）：

```sql
CREATE TABLE platform.prompt_asset (
    id UUID PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
    asset_key VARCHAR(64) NOT NULL,          -- 代码里引用的稳定标识，如 task-planner
    name VARCHAR(128) NOT NULL, description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE UNIQUE INDEX ... ON platform.prompt_asset (tenant_id, asset_key);

CREATE TABLE platform.prompt_version (
    id UUID PRIMARY KEY, asset_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    system TEXT, user_template TEXT,          -- {{var}} 占位，不做表达式
    params JSONB NOT NULL DEFAULT '[]'::jsonb,
    notes TEXT, published_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE UNIQUE INDEX ... ON platform.prompt_version (asset_id, version_no);
```

- 发布不可变；改 = 发新版本。
- 主站 `agent_run` 增 `prompt_ref VARCHAR(128)`（`key@v3`），由各 agent 配置项声明；没声明的记录 `null`，不强制。
- PROMPT 用例优先级：target.prompt > case.payload.system > 空。judge 也记 judge model + rubric 版本。

**验收**：改一版 prompt → 发布 → 指定该版本跑实验 → 报告里 candidate 显示 `task-planner@v4`，与 `@v3` 的实验中位数可直接比。

### 1.3 数据集编辑闭环与防污染 **[P0]**

现状缺口（都是小口子，但会让人放弃用）：

- 没有**单条用例**增/改/删——只能整体导入覆盖草稿。
- tags 建表了但没有任何写入口。
- 没有「从历史版本 fork 回草稿」——发布错了只能重导。
- 草稿是累积语义：`publish` 每次把 version_no=0 全量克隆，草稿永不清空。这是合理的（草稿=当前全集），但**需要一个显式的「我要基于 v2 改」入口**，否则第二次发布会把已删除的历史用例又带回来。

补：

```
POST   /datasets/{id}/cases              单条新增（草稿）
PUT    /datasets/{id}/cases/{externalId} 修改 payload/tags
DELETE /datasets/{id}/cases/{externalId} 删除（仅草稿）
POST   /datasets/{id}/versions/{v}/fork  v → 覆盖草稿（有草稿时需 confirm）
PUT    /datasets/{id}                    改名/描述
```

防污染：加一条硬约束——**评测集只允许三个写入口**（人工录入、文件导入、BadCase/trace 回流），不接受运行时自动沉淀；PROMPT 资产库的 few-shot 示例与评测集物理隔离（不同表，不做互相引用）。

### 1.4 执行可靠性 **[P1]**

现状：单线程调度器、单实验串行逐例、TASK 单例最长 15 分钟、无重试、无断点续跑、无预算上限。
一个 50 例的 TASK 实验理论上要跑 12 小时，且中途崩了只能全量重跑（upsert 幂等但不会跳过已完成用例）。

补：

- **并发**：实验级 `concurrency`（默认 1，PROMPT 可 4，TASK 上限 2）+ 全局 `eval.scheduler.max-concurrent-experiments`（默认 2）。调度从「单线程串行阻塞」改为「线程池提交，实验间并行、实验内按 concurrency 分片」。
- **续跑**：`POST /experiments/{id}/resume` 复用同一 experiment 行，runner 先 `SELECT case_external_id FROM eval_case_result WHERE experiment_id=?` 跳过已完成。
- **重试**：区分「评测器调用失败」（网络/超时/5xx → 重试 1 次，退避 2s）与「断言不通过」（不重试）。
- **预算**：`budget.maxCases`（抽样前 N 例）、`budget.maxCost`（累计超限 → 实验 `failed` + error_code=BUDGET_EXCEEDED）、`judgeSampleRate`（通过样本按比例抽 judge，失败样本 100% judge）。
- **超时**：per-case 超时独立于实验租约（PROMPT 90s、INTENT 30s、RETRIEVAL 60s、TASK 15min），HTTP 客户端读超时显式设置。

### 1.5 结果、报告、门禁 **[P1]**

现状：结果只有 `passed/checks/judge/latency_ms/answer/digest/snapshot`，totals 只有
`total/passed/failed/skipped/passRate/avgLatencyMs/judgeAvg`；对比只有两实验、逐例 delta；无成本、无聚合、无趋势、无导出、无门禁。

补：

```sql
ALTER TABLE platform.eval_case_result
    ADD COLUMN input_tokens  INTEGER,
    ADD COLUMN output_tokens INTEGER,
    ADD COLUMN cost_amount   NUMERIC(12,6);
ALTER TABLE platform.eval_experiment
    ADD COLUMN concurrency INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN budget      JSONB   NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN resume_of   UUID;
```

- totals 增 `totalCost`、`avgCostPerCase`、`judgeMin/Max`、`byTag{tag:{total,passRate}}`。
- 报告：`GET /experiments/{id}/report?groupBy=tag|check|dimension`（按标签、按断言名、按 judge 维度聚合失败分布）。
- 趋势：`GET /datasets/{id}/trend?limit=20` → 每次实验的 passRate/judgeAvg/cost/时间，用来做模型漂移检测。
- 导出：`GET /experiments/{id}/export?format=csv|jsonl`（流式，含逐例 checks）。
- 门禁：`GET /experiments/{id}/gate?minPassRate=0.9&maxAvgCost=0.05&maxAvgLatencyMs=30000`
  → `{pass, reasons[]}`。CI 用独立的只读令牌（新增 `X-Aster-Ci-Token`，只能读 experiment/gate），不复用内部执行令牌。

### 1.6 四类评估器的口径细化 **[P1]**

| 类型 | 现状 | 问题 | 建议 |
| --- | --- | --- | --- |
| INTENT | 判 `intent` 字符串相等 | 不看 confidence；把「该走任务却闲聊直达」和「该闲聊却走任务」等价处理，前者代价大得多 | 加 `expect.confidenceMin`；加 `weight`/`severity` 字段，false negative 记为 `critical` 并在 totals 单列 |
| RETRIEVAL | 按 `agentKey+documentId` 调 `verifyDocument`，判 `matched` | 测的是「某文档能不能被检索到」，不是检索质量；每例要人工填 documentId | 加一种 case 形态：`{question, expectDocIds[], topK}` → 调主站新增 `POST /api/internal/eval/retrieval-search` 返回 topK 列表，算 recall@k/MRR；旧的 verifyDocument 保留为 smoke |
| PROMPT | 直连 DashScope 裸 system+input | 测的不是线上；judge rubric 硬编码四维 | 走 1.2 的 prompt 资产；judge rubric 可配（维度/锚点文本），judge 结果记 `judgeVersion` |
| TASK | 内部 API 无头跑，9 条断言 | 无成本记录、无轨迹级指标（工具选择/顺序）、失败原因不分类 | digest 补 token/cost/step 数；加 `expect.tools[]`（应出现的工具）与 `expect.maxSteps`；失败分类落到 `error_code` |

统一约定：**同一套 rubric 既评生成质量也评数据质量**（调研 §7.1），judge 输出必须带 rationale，供人工复核。

### 1.7 安全与多租户修复 **[P0，小改动]**

已发现的实际缺陷：

1. `GET /api/ai/eval/datasets/{id}/cases` 没有校验 dataset 归属，直接调 `draftCases/publishedCases`。
2. `EvalDatasetService.publishedCases(tenantId, ...)` 与 `draftCases(tenantId, ...)` 的 SQL **没有 tenant_id 条件**，参数是摆设。
3. `deleteDataset` 的级联删除只按 dataset_id（归属校验在前，可接受）。

影响有限（需要 ADMIN JWT + 猜到 UUID，且当前单租户），但属于「设计上写了租户隔离、实现没做」，必须补。

另外补：
- 数据集/实验/发布的**操作审计**（谁在什么时候发布了 v3、取消了实验），落 `platform.audit_log` 或复用主站审计表。
- 数据中台只能读 `platform.user_feedback` + 只读 `agent_run`；评测中台禁止直读业务表（保持现状，别开口子）。

---

## 2. 数据中台（data-service）

### 2.1 反馈采集：显式 + 隐式 **[P0]**

现状：一次点击 👍/👎，无理由、无标签、不创作产物、无隐式信号。`source` 枚举预留了 `creative` 但无人写。

**显式**（保持「一次点击也要能提交」）：
- 点踩后浮出 4 个快捷标签：`不准确 / 不完整 / 跑题 / 格式差 / 太慢`，可多选可跳过。
- 标签写 `user_feedback.tags`（表已有），理由仍可后补（管理侧编辑）。

**隐式**（专家方案四件套，本项目的载体）：
- chat：`regenerate`（重发/继续）、`copy`、`export`。
- 创作：`adopt`（采纳）、`edit`（带编辑距离占比）、`export`、`regenerate`。
- 落独立轻表（append-only，与反馈解耦）：

```sql
CREATE TABLE platform.user_signal (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id   VARCHAR(64),
    event     VARCHAR(32) NOT NULL,          -- regenerate|copy|export|adopt|edit
    subject_type VARCHAR(16) NOT NULL,       -- run|asset|conversation
    subject_id   VARCHAR(64) NOT NULL,
    props     JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {distanceRatio, runId, assetId...}
    created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX ... ON platform.user_signal (tenant_id, event, created_at DESC);
```

埋点走主站异步（不阻塞 SSE），失败只记日志。**单次采纳成本** = media 成本 / adopt 次数，靠这张表算。

### 2.2 BadCase 从「状态」升级为「根因」 **[P0]**

现状：`open/acked/converted/ignored` 四态 + 自由文本 reason。没有根因、没有聚类、没有处理人，
BadCase 只是一个「待办列表」，不能反推系统该改哪一层。

补：
- `root_cause` 枚举：`knowledge_gap`（知识库缺失）/ `retrieval_miss`（检索没召回）/ `prompt_weak`（提示词弱）/
  `tool_args`（工具提参错）/ `intent_misroute`（意图误判）/ `refusal_fail`（该拒未拒）/ `latency`（太慢）。
- `assignee`（当前用户即可）+ `resolution`（一句话结论，转成评测用例时带进 notes）。
- 列表按 root_cause 聚合计数 → 直接回答「这个月最多的问题是知识库缺失」。

### 2.3 Trace 浏览与「从 trace 建用例」 **[P1]**

现状：主站有 `GET /ai/manus/conversations/{cid}/runs/{rid}/trajectory`，但数据中台完全没接，
所以调研里写的「从 Trace 回流数据集」这条主线在 UI 上是缺的。

补：
- 主站新增内部端点 `GET /api/internal/eval/trajectory?runId=`（内部令牌，返回聚合轨迹：run 元数据/计划/工具账本/模型调用/事件）。
- 数据中台「轨迹」页签：按 run 列表 → 时间线视图（计划步骤、工具调用、耗时、token）→ 一键「建用例」
  （抽 input/final answer/工具序列摘要 → 调 eval backflow）。

### 2.4 回流目标可选 **[P1]**

现状：只有一个固定归属「线上回流-单轮直调」PROMPT 数据集，👎 用例 reference 为空。

补：
- `POST /api/ai/feedback/{id}/convert` 增加 `{datasetId?, evalType?, expect?}`；缺省仍走默认数据集。
- 👍 → golden（reference=原回答）；👎 → regression（reference 空、judge 开、阈值 3.5）——保持现有规则，但允许选目标。
- 回流成功后把 `experimentId/caseExternalId` 也写进 feedback，让「这条反馈后来在哪个实验里验过」可查。

### 2.5 语料治理与偏好导出 **[P2]**

- 治理看板（先做能查的）：知识文档数/审核积压（读主库知识表）、反馈趋势、回流转化率、BadCase 根因分布、评测覆盖（哪些数据集最近没跑）。
- 偏好数据：`GET /export/preference-pairs?since=` → JSONL `{prompt, chosen, rejected, meta}`，只为「先存后用」，不建 UI。

---

## 3. 横切

### 3.1 迁移归属与启动校验 **[P0，小改动]**

`platform` schema 由主站 Flyway 拥有（现状正确）。但 eval/data 服务启动时不校验 schema 版本，
主站没升级就先起中台 → 运行时 SQL 报错，难排查。

补：两个服务启动时执行 `SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1`
并与编译期常量（`MIN_SCHEMA_VERSION`）比对，不满足则**启动失败并打印明确提示**；compose 加 `depends_on: backend: condition: service_healthy`。

### 3.2 资源隔离与成本护栏 **[P1]**

TASK 评测在主站进程内跑，与线上用户请求抢模型配额/CPU。补：
- 主站 `app.internal.eval.max-concurrent-runs`（默认 2）+ 独立线程池（评测低优先级）。
- 实验级 `budget.maxCost`（1.4）+ 超限即停，避免一次实验烧掉整月额度。
- judge 默认抽样（失败 100%、通过 20%），规则断言先行。

### 3.3 仓库同步 **[P0，待拍板]**

现状：monorepo `services/*` 与两个 public 仓库内容一致，无同步机制。建议 **monorepo 为唯一真源 + 单向镜像**
（`git subtree split` 推送到两个仓库），用脚本/CI 一条命令完成；不用 submodule（会把 monorepo 构建绑死在
外部仓库上，且与现有 `services/*` 独立工程范式冲突）。

### 3.4 可观测 **[P2]**

两个服务暴露 `/actuator/prometheus`（或最简 `/metrics` JSON）：实验队列深度、执行中实验数、单例耗时 P95、
judge 失败率、回流成功率。先不加 Prometheus 栈，页面顶部显示即可。

---

## 4. 批次与验收

| 批次 | 内容 | 验收标准 |
| --- | --- | --- |
| **E2-1 评测可信** | 1.1 target、1.2 prompt 资产、1.5 成本/token、1.3 编辑闭环、1.7 租户修复 | 同数据集对比 qwen-plus vs qwen-max，报告含逐例 delta + 成本；PROMPT 实验引用 `task-planner@v3`；数据集可单条增删改 |
| **E2-2 评测可运营** | 1.4 并发/续跑/重试/预算、1.5 聚合/趋势/导出/门禁、1.6 口径 | 50 例 TASK 实验可断点续跑；超预算自动停；`gate` 返回 pass/fail 可被脚本消费 |
| **D2 数据闭环** | 2.1 显式+隐式、2.2 根因、2.3 trace 浏览、2.4 回流目标、2.5 看板 | 从一条线上 run 到进入评测集 ≤1 分钟；BadCase 可按根因出分布；采纳成本可算 |

顺序理由：先把「测什么」和「测得准」解决（E2-1），否则数据回流进来的用例也没有可信的验证手段；
数据中台的 trace 浏览依赖评测中台的 target/回归能力先稳定。

---

## 5. 待拍板

1. **Prompt 资产库是否进 E2-1**——不进的话，「Prompt 变更出对比报告」这条验收标准只能靠手工复制 system 串。
   建议：进。
2. **门禁形态**——做成 API + CI 令牌（推荐，脚本可消费），还是先只在页面显示红绿灯。
3. **双仓库同步**——单向镜像（推荐）还是 submodule。
4. **在线评测（生产 run 抽样 judge）**——本期不做，还是 D2 顺带做一个 1% 抽样任务。

---

## 实施记录（2026-11-04）

### 已落地

| 提案项 | 实现位置 | 说明 |
| --- | --- | --- |
| 1.1 被测对象一等公民 | `EvalExperimentService.create()` + `TargetSpec` / `TargetResolver` | 快照写 `eval_experiment.candidate`（未新加列）：`kind/model/prompt{assetKey,versionNo,system,systemSha256}/agent/resolvedAt`；非 PROMPT 评估器传 `model/prompt` 直接 400 |
| 1.2 Prompt 资产库 | `platform.prompt_asset` / `prompt_version` + `PromptAssetService` | 版本不可变；创建实验时解析并冻结正文，历史实验不受后续改版影响 |
| 1.3 数据集编辑闭环 | `EvalDatasetService` 新增单条增删改、`PUT /datasets/{id}`、`POST /versions/{v}/fork` | 草稿可编辑，已发布不可变；fork 覆盖草稿需 `confirm=true` |
| 1.4 执行可靠性 | `EvalRunner` | 实验级并发（1-8）、续跑（复制来源结果后只补缺）、单例重试 1 次、预算（maxCases / maxCost / judgeSampleRate）、事件序号改全局序列（并发安全） |
| 1.5 结果与报告 | `eval_case_result.input_tokens/output_tokens/cost_amount` + `EvalReportService` | totals 增成本/token/critical；`/report?groupBy=tag\|check\|dimension`、`/trend`、`/gate`、`/export` |
| 1.6 评估器口径 | `com.zwx.aster.eval.evaluator.*` | 策略接口重构；INTENT 支持 `confidenceMin`；PROMPT judge 可配维度/指令并按预算抽样；TASK 记录 token |
| 1.7 租户修复 | `EvalDatasetService` / `EvalController` | 归属校验 + SQL 补 `tenant_id`（见 `docs/bugs/bug-2026-11-04-eval-dataset-tenant-scope.txt`） |
| 2.1 反馈标签 | `platform.user_feedback.tags`（沿用）+ 创建接口接收 tags | 点踩标签由前端后续接入；后端与列表展示已就绪 |
| 2.2 根因 | `root_cause/assignee/resolution` + `PUT /feedback/{id}/analysis` + `/stats` | 8 类根因枚举；看板按根因聚合好评/差评/已回流 |
| 2.4 回流带上下文 | `UserFeedbackService.backflowReason()` | 回流理由自动拼 `[根因:x] 处理结论 原始理由`，用例同时标记 `critical` |
| 2.5 隐式信号 | `platform.user_signal` + `UserSignalService` | 事件 `adopt/edit/regenerate/copy/export`，按事件聚合；埋点接入点在主站前端（未接） |

### 与提案的偏差（有意为之）

1. **target 不新增列**：`candidate` 本来就是被测对象快照，复用比加一列更简单；语义靠解析器保证。
2. **续跑新建实验而不是复用原行**：保留两次执行的完整审计链（`resume_of` 指回来源），代价是多一条记录。
3. **TASK 只记 token 不记成本**：主站内部 API 返回 token 汇总，但模型由智能体配置决定，eval-service 不知道单价，成本留空而不是猜。
4. **全局并发未做**：调度器仍一次认领一个实验，实验内并发已生效；多实验并行留到有真实排队压力时再加。

### 未做（下一批）

- Trace 浏览 / 从 trace 建用例（需主站新增内部轨迹端点 + 数据中台页面）
- 在线评测（生产 run 抽样 judge）
- 门禁 CI 只读令牌（当前 gate 用管理员 JWT）
- 前端埋点接入（重生成/复制/导出/采纳的实际上报点）
- 对话内点踩后的快捷标签 UI
