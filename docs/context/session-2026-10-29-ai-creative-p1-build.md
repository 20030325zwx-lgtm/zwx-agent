# 会话摘要：AI 创作 P1 实施（C00–C16，进行中）

## 执行规则回顾

- 按 docs/design/2026-10-29-ai-creative-implementation.md 任务卡推进，每批更新台账（§3）与验收记录 docs/test/results/2026-10-29-ai-creative-p1.md。
- 不自动 commit/push、不重启共享服务；真实收费调用先征求授权；Mock 不冒充真实验收。

## 已完成

### C00 基线与首个模型契约（A01 PASS）

- 基线 HEAD `94a53e8`；工作区差异＝本模块文档 + 其他会话副本文件（保留不动）。
- 复用既有实现：`PURPOSE_IMAGE`/`resolveImage`（前一会话已交付）；迁移 V1–V7，新增从 V8 起。
- 产出 docs/design/2026-10-29-ai-creative-model-contract.md：DashScope 文生图提交/查询契约、
  状态→Accepted/Completed/Rejected/Unknown 映射、脱敏响应样本、5 个未知项（U1–U5）及门禁影响。

### C01 数据迁移与领域契约（A02 PASS；A05 数据库约束部分覆盖）

- V8__creative_p1_tables.sql：creative_project/creative_draft/media_model_profile/media_job/
  media_job_attempt/media_job_event/media_job_output/creative_asset/creative_asset_link/
  media_job_asset_ref/media_usage_record/media_provider_quota 共 12 表 + 索引 + 默认配额。
- Java：creative/domain（10 个枚举、状态机、快照与行记录）+ repository（7 个 JdbcTemplate 仓库）
  + support/CreativeJson（null 安全）。
- 测试：MediaJobStatusTest（状态机 4 例）、CreativeMigrationTest（隔离库：空库迁移、V7→V8 升级保留
  旧数据、重复迁移无变化、跨用户外键 23503、幂等唯一键 23505）。

### C02 图片模型目录与能力（A03 PASS；A04 待 C04 Mock）

- ModelCatalogService：resolveImage 改为 resolveImageBinding（返回 config+providerId+modelRowId）；
  CAPABILITIES 增加 image_generation。
- MediaModelResolver：显式 modelRowId 优先 → 图片专用绑定 → 未绑定抛 MODEL_NOT_CONFIGURED（不回退 chat）；
  DashScope 生图绑定自动幂等补 media_model_profile（adapter=dashscope-text2image，VERIFIED）。
- ParameterSchema（dashscope 文生图 P1 schema）与校验：未知键/缺必填/类型/取值越界全部拒绝（BR07）。
- 测试：ParameterSchemaTest（4 例）、MediaModelResolverTest（4 例，PG）。

## 验证结果

- `mvn test -Dtest=MediaJobStatusTest,CreativeMigrationTest` → 7/7 通过。
- `mvn test -Dtest=ParameterSchemaTest,MediaModelResolverTest` → 8/8 通过。
- `mvn test`（全套，354+）→ 通过；V8 已应用到开发库（仅增量表）。

## 实施事故与教训

- 本会话曾把新 Java 文件写到 `src/main/java/com/zhuwenxuan/...`（包前缀笔误），
  并出现过“写入成功但文件随后消失”的现象；已全部迁移到 `com/zwx/zwxagent/creative` 并全局修正包名。
  教训：批量建文件后立刻 `ls` 复核 + 编译验证，再继续后续步骤。
- pgjdbc 对 Instant 需要 Timestamp.from 显式转换；JSONB 列 NULL 读取需 null 安全反序列化。

### C03 项目、草稿与归属（A05/A06 部分覆盖；全量待 C04/C06）

- CreativeProjectService：创建/查询/游标列表/更新（名称、描述、归档，乐观锁，仅 ACTIVE→ARCHIVED）。
- CreativeDraftService：创建（项目须 ACTIVE，锁项目行互锁）、读取、更新（expectedVersion → 409）、列表。
- CreativeProjectController（/api/ai/creative/projects|drafts）；ApiExceptionHandler 增加
  创作冲突 409 与领域异常（MODEL_NOT_CONFIGURED→400、UNSUPPORTED_PARAMETER→422）。
- 测试 CreativeProjectServiceTest（6 例，PG）：隔离、草稿乐观锁 409、归档拒绝新草稿、归档不可逆、
  名称长度校验、仓库往返。`mvn test -Dtest=CreativeProjectServiceTest` → 6/6 通过。


### C04 任务受理、幂等、快照（A07/A08/A09 PASS）

- MediaJobService：结构校验（operation/参考图拒绝/提示词上限 500）→ 幂等预检（锁项目行事务内，
  同键同内容复用、不同内容 409）→ 模型解析（BR03 不回退 chat）+ schema 校验 →
  受理事务（锁项目校验 ACTIVE + 排队配额 + 插入 QUEUED + JOB_CREATED 事件，同事务）。
- 插入冲突在事务外回读（PG 事务失败后不可继续查询）；并发由项目行锁天然串行化。
- CreativeJobController：POST /jobs（新建 202/复用 200，Idempotency-Key 必填）、GET /jobs/{id} 基础详情。
- 配额：USER max_queued 默认 20，media_provider_quota 行可覆盖；超限 429 不落库。
- 测试 MediaJobServiceTest（6 例，独立测试用户 + 专用 provider/model/binding，无真实调用）：
  A07 20 并发 1 job 1 事件；A08 改内容/换模型 409、复用冻结快照；A09 事件故障整体回滚 + 配额 429。
- 注意：image_generation 绑定的模型行会被自动补档案且 schema 由适配器刷新（revision 递增）。

## 遗留事项（C04 后更新）

- C01–C04 状态 IN_REVIEW（A07–A09 已 PASS；A05/A06 全量待 C06/C08 实体补齐）。
- 下一任务：C05（租约调度、配额互锁、重启恢复、Mock provider）；之后 C07 供应商适配与入库。
- 未提交：C00–C04 全部改动在工作区，等待用户授权提交。


### C05 租约调度、限流、恢复（A10/A11/A12/A13 PASS）

- MediaProviderAdapter 接口 + MockMediaProviderAdapter（进程内、可脚本化、记录 submit/query 次数）；
  生产适配器在 C07 注册（按 profile.adapter_key 路由）。
- MediaJobScheduler：认领（SKIP LOCKED + 租约 epoch）→ 按状态处理（QUEUED 提交、RUNNING 查询、
  FETCHING 入库、CANCEL_REQUESTED 取消）；配额按 GLOBAL→PROVIDER→USER 行锁顺序检查；
  全部写回带 version+epoch fencing；后台循环默认关闭（creative.scheduler.enabled）。
- MediaJobRecoveryService（recover()）：SUBMITTING 无 taskId 且租约过期 → RECONCILING；
  带 taskId 的任务恢复查询；不自动重发提交。
- Mock 入库 MockAssetIngestService（C06 换真实存储实现）。
- 测试 MediaJobSchedulerTest（5 例，PG/Mock）：A10 双实例并发配额、A11 fencing、A12 恢复不重发、
  A13 提交不明不重发。`mvn test -Dtest=MediaJobSchedulerTest` → 5/5 通过。
- 教训：text block 尾随空格会被剥离，SQL 拼接 AND 时注意；provider_id 为 BIGINT，
  作用域统计参数不要传字符串（bigint = varchar 报错）。

## 遗留事项（C05 后更新）

- C00–C05 完成（C01–C04 IN_REVIEW：全量验收在 C16 复核）；A14–A40 待 C06+。
- 下一任务：C06（文件存储与素材生命周期，OSS/本地适配）；之后 C07 供应商提交与结果入库。
- 未提交：全部改动在工作区，等待用户授权提交。


### C06 文件存储与素材生命周期（A14/A15/A16 PASS；A17 部分覆盖）

- MediaStorage 接口 + OssMediaStorage（复用 OssClientProvider）+ LocalMediaStorage（临时文件原子 rename，
  resolve 做越界校验）+ MediaStorageConfig（creative.storage.backend=oss|local 路由）。
- CreativeAssetService/Controller：上传（签名嗅探 PNG/JPEG/WebP、20MiB、40MP、伪造拒绝，失败不残留 PENDING）、
  内容读取（归属授权）、收藏/改名、软删除（活动任务引用 409）、游标列表。
- ImageInspector：PNG/JPEG 头解析尺寸 + WebP VP8X 解析。
- 测试 CreativeAssetServiceTest（6 例，独立服务栈 + 本地存储 + 独立 JDBC 连接）全绿；
  OSS 真实往返待 C16 真实环境补验；缩略图推迟到 C11 界面批次。

## 遗留事项（C06 后更新）

- C00–C06 完成（C01–C04/C06 IN_REVIEW：全量验收在 C16 复核）；A18+ 待 C07。
- 下一任务：C07（DashScope 适配器 + 产物入库，含 HTTP Mock 供应商）。
- 未提交：全部改动在工作区，等待用户授权提交。

### C07 供应商适配与结果入库（A19/A20/A21 PASS；A18 BLOCKED）

- DashScopeImageAdapter：submit/query 按契约实现；401/403→PROVIDER_AUTH_FAILED、429→throttled(Retry-After)、
  5xx/超时→Unknown、2xx 无 task_id→Unknown；取消返回 UNSUPPORTED。
- SafeMediaDownloader：逐跳校验 scheme/host/DNS，拒绝回环/私网/链路本地/169.254 元数据地址（元数据地址任何模式都拒绝），
  最多 4 跳，流式读取超过 maxBytes 立即中断。
- StorageAssetIngestService：真实写对象存储 + 素材行 + 产物槽位；槽位预先分配 assetId/objectKey，重试覆盖同一对象。
- 调度器 completeWithOutputs 支持 URL 产物下载；入库失败 INGEST_FAILED→FETCHING→SUCCEEDED，不重新提交。
- 测试：DashScopeImageAdapterTest 13 例、SafeMediaDownloaderTest 6 例；
  MediaJobSchedulerTest 新增 A19/A20 共 3 例，8/8 全绿（PG + LOCAL + MOCK）。

### C08 查询、取消、对账、事件（实现完成，用例未执行）

- MediaJobService：项目筛选分页、allowedActions、cancel（QUEUED 直接取消；RUNNING→CANCEL_REQUESTED；
  SUBMITTING/已生成拒绝）、retry（FAILED/CANCELLED；RECONCILING 需确认）、resumeIngest、事件游标。
- CreativeJobController：详情含 outputs/parameters/model；事件 JSON 与 SSE（seq 续传，终态关闭）。
- ModelCatalogService.deleteProvider：仍有在途媒体任务时拒绝删除（A25）。

### C09 提示词优化（实现完成，用例未执行）

- PromptOptimizationService + /ai/creative/prompts/optimize：只改写文本、不创建任务；容忍代码块/纯文本；
  畸形 JSON 或超长（>500）返回 422 且原文保留。

### C10/C11 创作界面（构建通过，浏览器验收未执行）

- 新增 frontend/src/views/creative：CreativeHome、CreativeProject、CreativeTasks、CreativeAssets + shared.js/creative.css。
- 路由 /creative、/creative/projects/:projectId、/creative/tasks[/:jobId]、/creative/assets；全局导航新增「AI 创作」。
- 创作台：三栏（结果区 + 参数区 + 顶栏 Tab）、草稿 1.2s 自动保存/409 冲突另存、优化建议采用/放弃、
  幂等键在网络失败重试时复用、按 allowedActions 显示取消/重试/重新保存/继续创作、任务卡状态文案不虚构进度。
- 素材：鉴权内容走 blob URL（不把 token 放 URL）、收藏/下载/删除/继续创作；工作台新增 AI 创作区块。
- `npm run build` 通过；A27–A30 未做浏览器交互验收。

### C12 智能体接入（实现完成，用例未执行）

- CreativeImageTool：`generateImage(prompt, size, projectId?)`，无项目时选择/创建「智能体创作」；
  只返回 jobId，不在工具内轮询；ToolFactory 仅在带 userId 的调用路径注册；`generateImage` 登记为 EXECUTE。
- 同一 run 的重复工具调用由 IdempotentToolCallback 按账本复用。

### C13/C14 工作流外部等待与兼容（迁移 PASS，端到端未执行）

- V9__workflow_external_wait.sql：workflow_run 新增 WAITING_EXTERNAL 与 graph_snapshot；
  workflow_node_execution（unique run+node，WAITING_EXTERNAL 索引）。
- WorkflowService：启动冻结图；media_image_generate 提交后返回等待并让出线程；
  resumeWaiting 用状态条件更新认领；advanceMediaNode/failMediaNode 推进节点；成功后从冻结图继续。
- WorkflowMediaResumeScheduler：每 5 秒按 media_job 终态推进；RECONCILING/INGEST_FAILED 保持等待。
- C14：新节点输出 JSON {jobId,assetId,mediaType}，支持 {{node.output.assetId}}；
  image_understand/poster_render 增加 imageAssetId（OSS 预签名 URL / 本地读取字节）；旧 image_generate 未改动。
- 测试：CreativeMigrationTest 新增 V8→V9 保留运行、等待状态约束、节点唯一、恢复认领唯一性，5/5 通过。

### C15 观测与桌面（实现完成，用例未执行）

- creative.submissions-enabled=false → 503 SUBMISSIONS_DISABLED，已有任务继续。
- Micrometer 计数：creative.jobs.submitted/succeeded/failed/ingest_failed/uncertain。
- GET /ai/creative/health：开关、存储后端、图片绑定、DB、本人 active/attention/ingestFailed 计数（无敏感字段）。
- OSS 凭据缺失时媒体存储回退本地目录并告警；运维手册 docs/design/2026-10-30-ai-creative-ops.md。

### 隔离环境浏览器验证（A22–A24、A27–A30、A35–A36 部分 PASS）

- 用途：不触碰用户共享的 8123/3000，也不读取其密钥；使用独立库表数据与测试账号。
- 后端：`target/aster-agent-0.0.1-SNAPSHOT.jar --server.port=8126 --management.server.port=8136`
  + `creative.scheduler.enabled=true`、`creative.storage.backend=local`、`creative.storage.local-dir=/tmp/aster-creative-ui`、
  `app.security.registration-enabled=true`；前端：`VITE_API_BASE_URL=http://127.0.0.1:8126/api npx vite --port 3001`。
- 模型：测试用户自建 provider/model 绑定，`media_model_profile.adapter_key=mock-text2image`，不发起真实生成。
- 实测：
  - 新建项目 → 输入 → 草稿自动保存 → 生成 → 2–5 秒完成 → 结果预览/下载/继续创作；
  - 刷新不重复提交；再次生成产生独立 job；
  - 无图片绑定 → 引导条 + 生成禁用；
  - 未绑定/绑定、任务/素材/详情抽屉、1440/1024/390 宽度、深色主题均检查；
  - `submissions-enabled=false` → 503 `SUBMISSIONS_DISABLED`；`/ai/creative/health` 返回就绪与计数；
  - QUEUED 取消幂等、CANCELLED 重试 parent 关联、ingest 恢复均通过 HTTP 实测。
- 本轮修复（均见 docs/bugs）：
  1. 作业列表 SQL 少空格导致 `$2ORDER`；
  2. 调度器不释放租约，查询被拖到 60 秒；
  3. 配额检查与占位不在同一事务，双 worker 可超限；
  4. `CreativeProject.vue` 漏导入 `getCreativeProject`；
  5. 自动保存与生成前保存并发导致 409；
  6. 入库时图片尺寸解析异常导致 INGEST_FAILED（改为 best-effort）。
- 布局调整：结果图最大 320px、素材卡片占位高度、任务表显示提示词、详情长 ID 换行、
  窄屏（<900）自动收起全局导航、移动端隐藏「全部素材」并限制标题宽度；实测 390 宽度无横向溢出。

### 创作类型通用化（2026-10-30 追加）

- 用户指出 AI 创作不应写死为图片；项目新增 `kind`（IMAGE/VIDEO/NOVEL/COMIC），V10 迁移默认 IMAGE，
  旧项目升级后按图片处理。
- 后端：`CreativeKind`、`CreativeProjectRecord/ProjectView/CreateProjectRequest`、仓库 SQL 全链路支持；
  `MediaJobService` 对非 IMAGE 项目拒绝 `text_to_image` 提交（422），避免把视频/小说项目当图片生成。
- 前端：首页标题改为「AI 创作」，文案覆盖图片/视频/小说/漫画；新建项目可选类型（当前仅图片可选，
  其余标注规划中并禁用）；项目卡片与创作台显示类型；任务/素材/工作台/路由文案同步。
- 验证：`mvn clean test` 415 通过（新增 V9→V10 默认 IMAGE 与非法类型约束用例）；前端构建通过。

### 调度器默认开启（2026-10-30 修复 P0）

- 问题：`creative.scheduler.enabled` 硬编码 false，正式服务与桌面端启动时未覆盖，任务永远停在 QUEUED。
- 修复：默认 `${CREATIVE_SCHEDULER_ENABLED:true}`，其余调度参数改为环境变量可覆盖；
  `src/test/resources/application.properties` 关闭调度，保持用例确定性；`MediaJobSchedulerTest` 显式关闭。
- 运维手册同步默认值与环境变量；新增 bug 记录 bug-2026-10-30-creative-scheduler-disabled-by-default.txt。
- 验证：`mvn clean test` 415 通过；正式服务 `/ai/creative/health` 应显示 schedulerEnabled=true；
  默认启动（仅覆盖本地存储）的隔离实例提交 Mock 任务可完成。

### C16 补充：来源追踪、SSE reset、取消反馈、缩略图与本地素材理解（2026-10-30）

- 来源追踪：`MediaJobService.create` 新增可信重载（sourceType/runId/nodeId），HTTP DTO 不暴露该字段；
  智能体工具传 AGENT+runId，工作流媒体节点传 WORKFLOW+runId+nodeId；详情/列表返回来源，前端详情抽屉展示。
- SSE/事件：`eventPage` 对越界游标返回 `reset=true` + 最新快照，负游标 400；SSE 连接先发 `job-reset` 再续传。
- 取消：上游 `UNSUPPORTED` 时回到 RUNNING，写 `blockedReason=UPSTREAM_CANCEL_UNSUPPORTED` 与
  `CANCEL_UNSUPPORTED` 事件；界面显示"上游不支持取消，仍可能产生费用"。
- 缩略图：`ThumbnailEncoder`（最长边 320，失败返回 null），上传与生成入库都 best-effort 生成；
  `/assets/{id}/thumbnail` 无缩略图 404，素材列表优先缩略图并回退原图；删除仍只软删记录，未做对象清理。
- 本地素材理解：`image_understand` 引用本地素材时改用视觉模型（bytes 直传），OSS 素材仍用 10 分钟预签名 URL；
  未配置 vision 绑定时明确报错，不再笼统拒绝本地存储。
- 文档：验收标准补充 `PASS（部分）` 口径；结果表更新 HEAD、A17/A22/A24/A31–A34 说明；台账同步 V10 与上述实现。
- 验证：`mvn clean test` 421 通过 / 0 失败 / 6 跳过；新增缩略图、来源、事件 reset、取消不支持用例。

### 进度核查 R1–R3 修复与清理（2026-10-30）

- R1：调度器新增每 30 秒周期恢复，避免快速重启时 SUBMITTING 任务因租约未过期而永久搁置。
- R2：释放租约增加 epoch 围栏（`releaseLease(id, expectedEpoch)`），旧 worker 不会清掉新 worker 的租约。
- R3：`LocalMediaStorage` 以真实路径为边界，读写删除拒绝符号链接逃逸；新增 `LocalMediaStorageTest`。
- 清理：删除两份过期的 `WorkflowEditor 2.vue` / `Workflows 2.vue` 副本（未被引用、早于当前版本）。
- 收录进度核查报告到 `docs/test/results/2026-10-30-ai-creative-progress-review.md` 与 `docs/context/`，并修正验收汇总为逐行核对的 16/11/12/1。
- 验证：`mvn clean test` 424 通过 / 0 失败 / 6 跳过。

## 环境注意事项

- application-local.yml 未读取密钥内容（仅测试解析 datasource 结构，不输出）。
- **重要**：`target/classes` 曾残留大量重复 class（7211 个 vs 275 源文件），导致 Spring 启动/测试 10–30 分钟；
  已 `mvn clean compile` 修复，class 数 452，@SpringBootTest 启动恢复到秒级。见
  docs/bugs/bug-2026-10-30-stale-duplicated-classes-slow-startup.txt。
- 迁移 V9 已应用到开发库（仅新增列/表）；保留其他会话副本文件（`WorkflowEditor 2.vue`、`Workflows 2.vue`）。
- 浏览器验收使用隔离测试账号与 Mock 模型；未读取/输出用户密钥；A18/A37/A38/A40 仍未执行。
- 隔离实例使用 8126/8136 与 3001；验收结束应停止，避免与共享的 8123/3000 混淆。
