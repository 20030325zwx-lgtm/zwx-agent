# 工作流导入 AI 创作 + 中转站生图接入（2026-10-31）

## 背景与决策

- 2026-10-30 第二次复核报告（`docs/test/results/2026-10-30-ai-creative-progress-recheck.md`）确认调度 R1/R2 两个 P1 未闭环；本批先修复（详见 `docs/bugs/bug-2026-10-31-scheduler-r1-submitting-recovery.txt`、`bug-2026-10-31-scheduler-r2-claim-token.txt`）。
- 原总体设计 §12/台账 C17/C18 计划在创作模块内做"独立创作画布"。**2026-10-30 与用户确认改为：复用现有完整工作流引擎（12 节点 + Vue Flow 画布），以"创作入口 + 项目绑定"形态并入 AI 创作**。C17/C18 由本方案承接，不再新建第二套画布。
- 模型配置对齐 ccswitch 体验：`model_provider` 体系已支持 `openai-compatible` 协议（chat 侧完整），本批补齐生图侧——OpenAI images 兼容适配器 + 中转站模型列表拉取。用户确认范围不含生图连通性测试与旧 image_generate 节点改造。

## 一、调度修复（阶段 0）

- **R2（认领令牌）**：`MediaJobRepository.claim` 改为 `UPDATE ... RETURNING`，返回冻结的 `ClaimToken(jobId, owner, leaseEpoch)`；`MediaJobScheduler.tick` 携带令牌，处理与释放一律用令牌 epoch，不再重读。回写继续走 version+epoch 双围栏。
- **R1（SUBMITTING 漏恢复）**：`findDueForClaim` 排除 SUBMITTING（普通 tick 不再空操作清租约）；`recover()` 候选扩为 `status='SUBMITTING' AND (lease_until IS NULL OR lease_until < now())`，锁内重校验后分流：有 `provider_task_id` → RUNNING 恢复查询，无 → RECONCILING。
- **同步适配器配套**（为中转站生图引入）：
  - `MediaProviderAdapter.syncSubmitTimeoutSeconds()`（默认 0=异步）；同步适配器提交前以 epoch 围栏延长租约（`extendLeaseFenced`，不动 version）覆盖整个 HTTP 窗口。
  - 同步接口没有上游任务号、无法对账：提交不明 → `reconcileUncertainFenced` 进 RECONCILING 并写 `observation_deadline_at`（`creative.scheduler.sync-observation-seconds`，默认 600s）；窗口过后仍无结果 → FAILED，错误文案明确"结果未知，费用可能已产生"，不假称未计费。
- 测试：`MediaJobSchedulerTest` 新增 `submittingJobSurvivesTickAndIsRecovered`、`staleWorkerUsesFrozenTokenAndKeepsNewWorkersLease`（MockitoSpyBean 阻塞慢任务提交，双 worker 时序复现）。
- 已知环境注意：测试 SQL 一律用 JVM 时钟时间戳绑定（`pastMillis`），不要写回 `now() ± interval`——认领查询以 JVM Instant 作参数，Docker Desktop VM 时钟漂移会造成跨时钟比较的偶发失败（本批已踩过）。

## 二、工作流 × AI 创作（阶段 1）

### 数据与后端
- `V11__workflow_run_project.sql`：`workflow_run.creative_project_id UUID NULL REFERENCES creative_project(id)` + `(tenant_id, user_id, creative_project_id)` 索引；NULL=未绑定，历史运行不受影响。
- `POST /ai/workflows/{key}/runs` 请求体新增可选 `projectId`，`WorkflowService.start` 经 `CreativeProjectService.get` 校验归属后落库；`RunContext`/`WorkflowRunRow`/`WorkflowRunView` 透传 `creativeProjectId`。
- `GET /ai/workflows/runs?projectId=`：按项目筛选运行（项目详情页工作流 tab）。
- **来源修正**：`startMediaImageTask` 改用 7 参 create（`MediaJobService.create(actor, key, projectId, request, JobSourceType.WORKFLOW, runId, nodeKey)`）——工作流发起的媒体任务不再被记为 UI 来源；项目归属优先级：运行绑定项目 > 节点 `projectId` 参数 > 兜底（首个 ACTIVE 或自动建"工作流创作"）。
- **产物归集**：运行绑定项目时，`poster_render` 产物经 `CreativeAssetService.upload` 登记进项目素材库（尽力而为，失败只告警不阻塞工作流）；`file_export` 文档保持工作流产物。

### 前端
- `CreativeHome.vue`：kind-strip 新增"工作流创作 · 已开放"chip + 独立入口卡；弹窗选工作流 + 目标项目 + 输入关键词 → `startWorkflow(key, keyword, projectId)` → 跳转 `/creative/projects/:id?tab=workflow&runId=...`。不新增 CreativeKind，不动 `CreativeKind.enabled()` 门禁。
- `CreativeProject.vue`：新增「工作流」tab——绑定本项目的运行列表（状态、步骤时间线、展开加载产物签名 URL 预览/下载、打开画布），运行中每 3s 轮询，深链 runId 自动展开。
- `WorkflowEditor.vue`：节点帮助文案更新——旧 image_generate 标注仅 DashScope，新流程引导用 media_image_generate（可用中转站模型）。

## 三、中转站生图接入（阶段 2）

### 适配器
- `OpenAiImagesMediaProviderAdapter`（key=`openai-images`）：`POST {base}/v1/images/generations`，body `{model, prompt, n:1, size?}`，**不发 response_format**（gpt-image-1 拒绝且恒返 b64_json；dall-e 系默认 url）；`data[]` 逐项解析 `b64_json`（解码为字节）或 `url`（交调度器经 SafeMediaDownloader 下载）。同步调用直接返回 Completed。
- 错误分类与 A21 对齐：429(+Retry-After)→throttled 可退避重提、401/403→PROVIDER_AUTH_FAILED、4xx→rejected（取 `error.code/message`）、5xx/超时/断连→unknown。
- 路径约定与 `DynamicChatModelFactory` 一致：baseUrl 以 `/v数字` 结尾直接拼，否则补 `/v1`（兼容裸域名与 `/api/paas/v4` 等写法）。
- DashScope 风格 `1024*1024` 自动归一为 `1024x1024`。
- 超时 `creative.adapters.openai-images.timeout-seconds`（默认 240），同时是租约延长窗口。

### 协议映射与 schema
- `MediaModelResolver.ensureProfile` 泛化为协议→适配器映射：`dashscope`→`dashscope-text2image`（VERIFIED，真实 Key 已验证）、`openai-compatible`→`openai-images`（**UNVERIFIED**，未经真实出图验证不标绿）；Mock 等其他适配器接管的档案尊重现状；未知协议明确拒绝（不变）。
- `ParameterSchema.openAiImages()`：size 为自由字符串（2–32 字符，WxH）——中转站代理的模型各有尺寸范围，闭集会误拒；n 固定 1、无 watermark。提示词上限维持全局 500。
- 调度器 `resolvedFromSnapshot` 的 schema 兜底按冻结快照协议选择。

### 拉取模型列表（ccswitch 式）
- `POST /settings/providers/{id}/fetch-models`：仅**个人** openai-compatible 供应商可拉取（系统供应商是共享数据，不允许批量写入）；调 `GET {base}/v1/models`，`data[].id` 去重排序后增量 upsert 进 `model_provider_model`（默认 `enabled=false`、无能力标记），返回 `{total, added, models}`；401/403、非 2xx、连接失败如实报错。
- `ModelCatalogSettings.vue`：openai-compatible 供应商卡片显示「从中转站拉取模型列表」；模型行的能力改为可编辑多选（新增「图片生成」选项），拉取→启用→勾选生图→绑定"图片生成"用途即接入创作链路。

### 使用链路
模型勾选 `image_generation` 能力并绑定用途后，创作台生成 / Agent `generateImage` 工具 / 工作流 `media_image_generate` 节点经 `MediaModelResolver` 自动可用，无需额外改动。

## 明确不做 / 遗留

- 旧 `image_generate` 工作流节点保持 DashScope 硬编码（仅改帮助文案引导）；生图连通性测试按钮未做（用户确认范围外）。
- `fetch-models` 不区分模型类型，模型分类交给用户勾选能力；后续可按名称启发式预选。
- 真实中转站出图验证：**2026-10-31 已通过**（KKAI 中转站 / gpt-image-2.5-sunburst，真实计费一张，SUCCEEDED，b64 直接入库）。过程修复两处：VPN fake-IP 被模型地址公网校验误拦（见 docs/bugs/bug-2026-10-31-vpn-fakeip-model-endpoint-validation.txt）、创作台 watermark 参数按协议感知发送（BindingView 增加 protocol）。openai-images profile 默认仍标 UNVERIFIED（不同中转站/模型差异大，逐模型真实验证后再人工升级）。
- 创作台 size 选择器仍是 DashScope 固定枚举；中转站模型的自由尺寸可在后续把选择器改为读 profile schema。

## 验证记录

- `MediaJobSchedulerTest` 12/12（含 R1/R2 新用例）；`OpenAiImagesMediaProviderAdapterTest` 10/10（b64/url 解析、401/429/4xx/5xx 分类、路径约定、同步超时声明）；`MediaModelResolverTest` 5/5（含 openai-compatible→openai-images 映射与 UNVERIFIED）；`ModelCatalogFetchModelsTest` 2/2（增量入库/去重/幂等/协议限制）；`CreativeMigrationTest` 7/7（含 V11 外键与旧运行保留）。
- 前端 `npm run build` 通过；后端 `mvn -DskipTests package` 通过，本地服务 `GET /api/health` 返回 ok。
- 相关 bug 记录：`docs/bugs/bug-2026-10-31-scheduler-r1-submitting-recovery.txt`、`docs/bugs/bug-2026-10-31-scheduler-r2-claim-token.txt`。
