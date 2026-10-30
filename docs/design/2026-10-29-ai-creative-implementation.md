# AI 创作模块业务实施细则与智能体执行手册

日期：2026-10-29。状态：待实施；所有任务初始状态为 TODO。

配套：[总体设计](./2026-10-29-ai-creative-studio.md)｜[验收标准](../test/2026-10-29-ai-creative-acceptance.md)。本文负责“按什么顺序、具体做什么、如何证明完成”；总体设计负责模块边界。出现冲突时，本文的明确补充决策优先，但不得把未实现契约解释为现状。

## 1. 当前基线、目标与执行规则

本轮只读核对基线 HEAD 为 `94a53e8`，接手时必须重新核对，不固定开发分支。现有前端副本文件 `WorkflowEditor 2.vue`、`Workflows 2.vue` 不是本模块交付物，不删除、不引用为主实现。

最新源码已包含 `ModelCatalogService.PURPOSE_IMAGE`、`resolveImage(userId)` 和前端“图片生成”用途绑定。旧 image_generate 优先使用专用绑定，缺失时仍回退 chat + 节点 model，轮询仍占工作流线程。前一会话记录 wanx2.1-t2i-plus/turbo 已成功生图，属于历史验证，不等于新媒体链路已通过。

**发布目标分级：** P1 为图片创作可用版（C00–C16）；P2 为独立画布与单镜头视频（C17–C20）；P3 为短剧与成片（C21–C24）。不能用 P1 验收结果宣称 P2/P3 已实现。

### 1.1 每个智能体接手时

1. 读仓库 AGENTS.md、最新 session 摘要、本文、验收标准，检查 git status 与现有迁移。
2. 找到首个“依赖已完成”的 TODO；已有实现先审查并补验收，不重复造模块。
3. 把当前任务改为 IN_PROGRESS，填写执行人或会话标识、起始提交与计划范围。并发协作只在用户授权且文件边界清晰时进行；本手册不自动授权创建子智能体。
4. 按任务卡实现一个可独立评审的批次；先满足当前目标，不顺手重写工作台或模型体系。
5. 执行指定验收用例并记录实际命令、环境与结果；失败先修复，发现并修复 bug 按仓库规范记录。
6. 更新执行台账与 session；最终报告交付物、证据、遗留项。不能凭编译成功把业务标为 DONE。

不读出密钥、不覆盖 application-local.yml，不清理他人修改。新增迁移使用实施时最新序号，历史已执行 SQL 不改写。

### 1.2 状态与完成定义

- TODO：未开始；IN_PROGRESS：正在实施；BLOCKED：缺少明确外部依赖，必须写原因和仍可推进内容。
- IN_REVIEW：实现与证据齐备，等待复核；DONE：相关验收通过且文档、交接更新。
- 没有真 Key 可以做 Mock 和本地可靠性测试；真实供应商相关门禁保持 BLOCKED，不能用 Mock 冒充。
- 文档中出现的新增类、表、工具、端点均为拟议名称；实现中调整名称时同步契约与验收映射。

## 2. 业务规则定稿（后续实现不得各自猜测）

| ID | 决策 |
| --- | --- |
| BR01 | P1 每任务输出 1 张；项目为个人私有，归属取 CurrentActor 的 tenantId/userId |
| BR02 | 创作项目 ACTIVE 才能新增草稿、上传或生成；ARCHIVED 保留查看、下载与在途任务收尾；归档不触发取消 |
| BR03 | 新创作模块只能使用 image_generation 模型；显式 modelRowId 必须可访问且能力匹配；未显式选择则 resolveImage；不得回退 chat |
| BR04 | 旧工作流暂保留旧回退，迁移到新节点时显示配置差异，不能直接改旧图语义 |
| BR05 | 项目名 trim 后 1–80 字符；提示词 1–8000 Unicode code point；超出适配器限制时按更严格限制拒绝；草稿允许空提示词 |
| BR06 | effectivePrompt 是实际提交内容，originalPrompt 是用户起始想法；优化结果只有用户采用后才进入 effectivePrompt |
| BR07 | 输入参数仅接受能力 schema 声明的键；不支持参数返回 422；固定 count=1；P1 不开放未验证的 seed、negativePrompt、图生图 |
| BR08 | P1 允许上传图片到素材库。text_to_image 不接受 referenceAssetIds；视觉理解另选 vision 模型；不能忽略参考图却称已参考生成 |
| BR09 | 相同幂等键只对应同一业务请求；网络重试复用，用户“再生成”使用新键并记录 parentJobId |
| BR10 | 请求成功仅表示创建 job；SUCCEEDED 必须有持久化产物可读；保存失败不再生图 |
| BR11 | 普通 retry 仅适用于已确认失败任务，创建新 job；RECONCILING 不提供普通重试，只提供查询／显式接受重复计费风险后的独立新建 |
| BR12 | 支持原样再次生成不等于像素级复现；随机性、模型升级和供应商行为都可能改变结果 |
| BR13 | 取消默认不退款；无上游取消能力时不显示“已取消”，继续追踪完成结果 |
| BR14 | 生成结果自动入库；收藏不影响持久化；删除为软删除；被活动任务引用时返回 409 |
| BR15 | 项目、模型配置、素材删除与提交必须事务互锁，不能校验后放任目标被并发删除 |
| BR16 | 原始文件保持私有；下载前校验所有者；不持久化签名 URL；管理员不能自动读取别人的创作 |
| BR17 | 费用未知保存 NULL；测试连接不生成图片，真实生成测试有独立按钮与计费说明 |
| BR18 | 草稿和画布用 expectedVersion；冲突 409，不自动用服务器值覆盖本地文本 |
| BR19 | job 被系统受理后输入和模型身份冻结；用户改全局绑定不改变在途任务 |
| BR20 | 新模块开关关闭仅拒绝新提交，已有任务查询、结果保存和下载继续；紧急暂停调度使用单独开关 |

### 2.1 DTO 与接口补充

总设计中的接口继续有效，补充以下最小约定（均以 `/api/ai/creative` 为前缀）：

- POST `/jobs`：新建返回 202；同键复用返回 200，均含 jobId/status/reused/version/links。
- GET `/jobs/{id}`：返回 stage、status、attentionRequired、blockedReason、assets、error、allowedActions 和 nextPollAfterMs；UI 不自行从状态猜操作权限。
- POST `/jobs/{id}/reconcile`：仅排入非生成型对账，返回 202；不触发重新提交。
- POST `/jobs/{id}/retry`：新 Idempotency-Key；复制不可变输入，重新校验当前权限与同模型可用性；若参数／模型需修改则改走新建草稿，不悄悄切换。
- POST `/jobs` 的可选 parentJobId 记录重新生成来源；旧任务不确定时必须附 acknowledgePossibleDuplicateCharge=true，且页面明确让用户选择。API 仍校验父任务归属。
- GET `/jobs/{id}/events`：接受 after 或 Last-Event-ID，两者同时存在必须相等，否则 400；seq 在 JSON 和 SSE 中用十进制字符串，避免 JS 大整数精度丢失。
- POST `/prompt-optimizations`：输入 projectId、prompt、mode(refine/expand/style)、可选目标模型标识；返回 optimizedPrompt、changes、assumptions；P1 不接收图片。
- POST `/assets/uploads`：multipart 包含 projectId 与 file；成功 201 返回 assetId/type/status；P1 在文件校验和存储成功后返回。
- PATCH `/projects/{id}`：允许 name/description/status/expectedVersion；没有硬删除项目接口。

错误响应新增领域异常映射，保留既有 `error` 字符串，同时增加 code、requestId、retryable；不要全局改变旧控制器响应。列表统一 `{items,nextCursor}`，以 `(created_at,id)` 降序游标分页，limit 1–100。DTO 不接受 tenantId/userId/sourceRunId 等来源身份；这些由可信服务入口注入。

### 2.2 两处可靠性补充

**事件序号：** PostgreSQL BIGSERIAL 分配顺序不等于提交顺序。同一 job 的状态与事件必须先锁 job 行，再分配 seq、写事件并一起提交；不允许独立事务写同 job 事件。订阅先读取已提交事件再继续轮询；通知只作唤醒，不作事实源。

**产物键：** worker 进入 FETCHING 前在 DB 建立 `media_job_output(job_id,output_index,asset_id,ingest_state,storage_backend,object_key,sha256,byte_size,last_error)`，唯一 `(job_id,output_index)`，持久化预分配 assetId。统一使用总设计的 `creative/{tenant}/{user}/{project}/{asset}/original.ext`，重试从该记录取 key，不重新随机生成。

**输入引用：** 增加 `media_job_asset_ref(job_id,asset_id,role)`，复合归属外键／事务校验，索引 asset_id；不能只依赖 input_snapshot JSON 做“在用”判断。P1 上传与生成来源仍可通过关系表追溯；P2 引入参考时直接复用。

**不确定任务：** 额外记录 observation_deadline_at、reconcile_after、manual_resolution。默认不自动判失败；用户可“停止跟踪”并标 attentionRequired，但不得假称供应商停止。手工释放本地槽位必须独立审计、明确上游可能仍执行；此时仅承诺本地已知在途限制，不能承诺上游绝不超并发。

## 3. 执行台账与依赖

更新状态时保留 ID。证据列填验收记录路径＋实际提交，不填“已测”两字。

| ID | 工作包 | 依赖 | 状态 | 证据 |
| --- | --- | --- | --- | --- |
| C00 | 基线与首个模型契约 | 无 | DONE | docs/design/2026-10-29-ai-creative-model-contract.md（A01）；docs/test/results/2026-10-29-ai-creative-p1.md |
| C01 | 数据迁移与领域契约 | C00 | IN_REVIEW | V8__creative_p1_tables.sql；creative/domain+repository+support；CreativeMigrationTest（A02 PASS：空库迁移、V7→V8 升级保留旧数据、重复迁移无变化、跨用户外键与幂等唯一键）；A05 完整用例待 C03+ |
| C02 | 图片模型目录与能力 | C01 | IN_REVIEW | MediaModelResolver/MediaModelProfileRepository/ParameterSchema；MediaModelResolverTest（A03 PASS：用户绑定、显式行、无绑定阻断、越权拒绝）；A04 待 C04 Mock |
| C03 | 项目、草稿、权限 | C01 | IN_REVIEW | CreativeProjectService/CreativeDraftService/CreativeProjectController；CreativeProjectServiceTest（隔离、草稿乐观锁 409、归档拒绝新草稿、名称校验）；A05/A06 全量验收待 C04/C06 的 job/素材实体 |
| C04 | 任务创建、幂等、快照 | C02,C03 | IN_REVIEW | MediaJobService/CreativeJobController；MediaJobServiceTest（A07：20 并发同键 1 job+1 事件；A08：同键改内容/换模型 409、复用返回冻结快照；A09：事件写入故障整体回滚、排队上限 429 无半成品）；完整环境复验在 C16 |
| C05 | 租约调度、限流、恢复 | C04 | DONE | MediaJobScheduler/MediaJobRecoveryService/MockMediaProviderAdapter；MediaJobSchedulerTest（A10：双实例并发 + 用户在途上限 1；A11：旧 epoch 回写被拒；A12：恢复查询不重发；A13：提交不明 → RECONCILING 不重发）；复查修复：动作结束释放租约（不再被 60s 租约阻塞轮询）、配额检查+占位同事务持锁、启动调用 recover()；双实例压测在 C16 复验 |
| C06 | 文件存储与素材 | C01,C03 | IN_REVIEW | MediaStorage/OssMediaStorage/LocalMediaStorage/MediaStorageConfig；CreativeAssetService+Controller；ImageInspector；CreativeAssetServiceTest（A14：PNG/JPEG/WebP 上传与伪造/超限/巨像素拒绝；A15：遍历拒绝+越权；A16：活动引用 409 + 终态后软删除；A17：本地往返 ✓，OSS 往返待真实环境）；缩略图待 C11 |
| C07 | 供应商与结果入库 | C05,C06 | IN_REVIEW | DashScopeImageAdapter + SafeMediaDownloader（逐跳 SSRF 校验、流式限额）+ StorageAssetIngestService（真实入库、同槽位重试）；DashScopeImageAdapterTest 13 例 / SafeMediaDownloaderTest 6 例 PASS（A21 分类：401/429+Retry-After/5xx/超时/无 task_id/私网与元数据拦截/超限中断）；MediaJobSchedulerTest 覆盖 A19/A20；A18 真实模型调用 BLOCKED（待费用授权） |
| C08 | 查询、取消、对账、事件 | C07 | IN_REVIEW | MediaJobService 增加列表/取消/重试/恢复入库/事件游标；CreativeJobController 增加项目筛选、详情 allowedActions、取消/重试/resume-ingest、事件 JSON 与 SSE；ModelCatalogService 删除供应商前检查在途媒体任务（A25）；隔离实例实测：QUEUED 取消/幂等/事件顺序、CANCELLED 重试 parent 关联、resume-ingest 恢复（A22–A24 部分 PASS）；浏览器实测 allowedActions 与详情 |
| C09 | 提示词优化 | C02,C03 | IN_REVIEW | PromptOptimizationService + /ai/creative/prompts/optimize：只做文本改写、不创建任务；畸形 JSON 容错解析、超长/失败 422 且保留原文；解析规则 4 例通过；A26 因可能计费未执行 |
| C10 | 创作入口与草稿界面 | C02,C03 | IN_REVIEW | frontend/views/creative（首页、创作台、任务、素材）+ /creative 路由与全局导航；草稿自动保存/409 冲突另存、无模型引导、优化建议采用；已通过 `npm run build`；隔离环境浏览器实测 A27 部分 PASS（无绑定引导、自动保存、刷新保留、窄屏/深色） |
| C11 | 生成、任务与素材界面 | C08,C09,C10 | IN_REVIEW | 任务卡按 allowedActions 展示取消/重试/重新保存/继续创作；诚实状态文案、SSE 失败退化轮询；素材库筛选/收藏/下载/删除/继续创作；浏览器实测 A28/A29/A30 部分 PASS（刷新/再生成不重复、状态文案、素材操作）；弱网、多状态截图未执行 |
| C12 | 超级智能体接入 | C08,C11 | IN_REVIEW | CreativeImageTool 注册到 ToolFactory（含 userId 的调用路径），ToolRiskCatalog 登记 generateImage=EXECUTE；复用 IdempotentToolCallback 防同一工具调用重放；A31 未执行 |
| C13 | 工作流持久化等待 | C08 | IN_REVIEW | V9 迁移（WAITING_EXTERNAL、graph_snapshot、workflow_node_execution）；WorkflowService 冻结图、media_image_generate 等待、resumeWaiting 条件认领、advance/fail 推进；WorkflowMediaResumeScheduler 轮询媒体终态；CreativeMigrationTest 新增 V8→V9 保留运行与认领唯一性用例 PASS；A32 端到端未执行 |
| C14 | 工作流节点与兼容 | C13,C11 | IN_REVIEW | 新节点 media_image_generate 输出 {jobId,assetId,mediaType}，支持 {{node.output.assetId}}；image_understand/poster_render 增加 imageAssetId（OSS 短期 URL / 本地字节）；旧 image_generate 未改动；编辑器新增节点与说明；A33/A34 未执行 |
| C15 | 桌面、发布与观测 | C11,C12,C14 | IN_REVIEW | creative.submissions-enabled 开关（503 拒绝新任务、旧任务继续）；Micrometer 计数（submitted/succeeded/failed/ingest_failed/uncertain）；/ai/creative/health 就绪与本人任务计数；OSS 凭据缺失回退本地目录；隔离实例实测 503 开关与 /health（A35/A36 部分 PASS）；桌面与备份演练未执行 |
| C16 | P1 总验收 | C15 | TODO | — |
| C17 | 自由画布数据与编辑 | C16 | TODO | — |
| C18 | 画布执行与依赖冻结 | C17 | TODO | — |
| C19 | 视频适配与长任务 | C16 | TODO | — |
| C20 | P2 组合验收 | C18,C19 | TODO | — |
| C21 | 剧本、角色与分镜 | C20 | TODO | — |
| C22 | 分镜批量生产 | C21 | TODO | — |
| C23 | 时间线与渲染 | C22 | TODO | — |
| C24 | P3 总验收 | C23 | TODO | — |

## 4. P1 逐步实施卡

### C00：锁定实施基线与模型契约

**目标：** 确认哪些能复用、首个图片模型如何真实工作。

**位置：** ModelCatalogService、WorkflowService、ModelCatalogSettings.vue、最新迁移、docs/context；新增契约记录到 docs/design/。

**步骤：** 记录 HEAD／工作区差异和已实现 resolveImage；通过已配置目录确认模型状态但不输出 Key；读取供应商当前官方文档，记录所选模型、区域、submit/query 路径、请求字段、返回状态、计费来源、取消与幂等支持（未知即写未知）；准备脱敏成功／失败响应样本。已有历史生图结果只作为参考，不重复计费以“熟悉接口”。

**产物：** 可供适配器使用的契约表、Mock 响应样本、真实测试前置条件清单。不能把示例 modelId 当成用户配置。

**验证／完成：** A01；关键未知项能分成“阻止真实 adapter”和“不阻止 Mock 底座”。真实凭据不足不阻断 C01–C06 的独立工作。

### C01：建立数据与状态契约

**目标：** 先使项目、任务、事件和素材的数据约束成立。

**位置：** 新增 creative/* DTO/enum/repository；新增 Flyway 迁移与 src/test 下数据库测试。

**步骤：** 总设计的 P1 表分批迁移，加入 media_job_output/media_job_asset_ref；为用户归属增加组合唯一约束和对应外键；job 请求与模型快照不可变；版本默认 1；所有状态枚举集中定义；新增状态迁移函数只允许总设计合法边；归属不匹配拒绝，禁止靠级联删除清理任务历史。

**补充约束：** media_job.source_type 取 UI/AGENT/WORKFLOW/TEST，可信服务填写；model快照保留历史，provider删除先校验活动任务；素材 READY 之前不能作为生成输入；媒体元数据空值允许尚未提取，不能填虚构宽高。

**产物：** SQL、DTO、状态迁移表、数据库测试；数据库不保存 URL 密钥和原始响应大对象。

**验证／完成：** A02、A05；空库与旧基线升级通过、重复迁移无变化；不用 H2 验证 PostgreSQL 锁／JSON／索引。

### C02：完善模型能力与专用解析

**目标：** 正确显示与选用图片模型，不破坏现有用途绑定。

**位置：** ModelCatalogService、ModelCatalogController、ModelCatalogSettings.vue；新 media_model_profile 与 MediaModelResolver。

**步骤：** 复用 PURPOSE_IMAGE，不重复添加；能力校验增加 image_generation；旧已绑定图片模型经已知 adapter 映射补 profile，不能把全部 DashScope 模型标为生图；添加显式模型选择解析并保持本人／系统可访问规则；返回参数 schema 与 verified 状态；UI区分配置校验和真实生图测试。

**特别注意：** 现有 resolveModelRow 返回 chat 用途语义，新服务应组合已有权限校验并构造媒体配置，不能只因能 resolve 就跳过生图能力检查。目录变更让新请求重新解析，在途任务使用快照。

**产物：** 可用媒体模型列表、schema校验、绑定回归测试。

**验证／完成：** A03、A04；没有专用绑定时新模块明确报错，旧工作流回退仍可读。

### C03：项目、草稿与归属

**目标：** 用户可以持久化自己的创作上下文。

**位置：** CreativeProjectService/Controller、CreativeDraftService；对应 repository。

**步骤：** 按 BR02/BR05 建项目；草稿保存 operation、prompt、参数、模型引用，version比较更新；列表使用稳定游标；所有路径从 actor 派生归属；ACTIVE→ARCHIVED 不取消在途任务；归档与提交锁同项目行，避免校验竞争。

**产物：** 项目／草稿 API 和权限、冲突测试。

**验证／完成：** A05、A06；第二用户无法读写、草稿冲突不覆盖、归档拒绝新任务。

### C04：任务受理与幂等

**目标：** HTTP创建快速返回、同一意图不重复收费提交。

**位置：** MediaJobService/Controller、请求验证器、job/event repository。

**步骤：** 鉴权→规范化请求→锁项目/模型/引用素材→查询或抢占幂等唯一键→校验能力／配额→冻结输入及模型→写QUEUED与事件→提交事务→返回；期间禁止调用生成网络接口。并发唯一冲突回读原记录。请求哈希采用稳定字段排序，reference顺序有意义不得排序丢失；不包括时间、requestId和短期URL。

**规则：** 原任务仍存在时，同键同内容可直接读回，即便目录后续变更；显式修改模型或内容构成新意图。隐式模型选择用第一次落库快照，后续同键回放不重新选择模型。超过本地排队上限429不创建半成品job。

**产物：** 创建接口、可信 source 上下文、幂等/权限/原子性测试。

**验证／完成：** A07、A08、A09；20次同键并发只有一条任务及一个初始事件。

### C05：调度、租约与重启恢复

**目标：** 后台可横向并发执行，崩溃不会触发盲目重发。

**位置：** MediaJobScheduler、MediaJobRecoveryService、QuotaRepository；Mock adapter。

**步骤：** 短事务 SKIP LOCKED选取、锁配额、写attempt/submissionKey/leaseEpoch；事务外调用；带epoch/version提交结果；QUERY/FETCH与SUBMIT线程池分开；按nextActionAt调度，不用Thread.sleep等待完整生成；恢复有taskId任务为QUERY，无taskId的过期SUBMITTING为RECONCILING。

**配额：** 以scopeType+scopeId表统一 GLOBAL/PROVIDER/USER，唯一键防重复；占用按固定scope顺序加锁，三层同事务检查；job持有inflight_reserved标记，释放幂等；QUEUED只占排队数量，provider已确定终态即释放上游槽位，FETCH另有下载并发限制。

**产物：** 持久化轮询、fencing、配额与重启测试。未完成DB事务不能通知前端完成。

**验证／完成：** A10、A11、A12、A13；模拟两个调度实例、旧worker迟到和中途kill，结果可审计。

### C06：存储与素材生命周期

**目标：** 云端与桌面都能保存和授权读取素材。

**位置：** MediaStorage、OssMediaStorage、LocalMediaStorage、CreativeAssetService/Controller；不重写旧知识库图片存储。

**步骤：** 流式put/open/stat/delete；校验PNG/JPEG/WebP实际签名、大小和像素限制，初版拒绝SVG/动画等未支持格式；本地临时文件写入后原子rename；建立metadata后READY；实现收藏/重命名/软删除；引用素材与删除锁同asset行；内容接口进行权限校验，不向浏览器返回磁盘路径。

**产物：** 上传、内容访问、缩略图与列表API；孤儿文件清理仅处理本模块前缀，配置宽限期且先有dry-run报告。

**验证／完成：** A14、A15、A16、A17；应用目录外的路径、符号链接跳转、超大像素文件均拒绝。

### C07：供应商提交、查询与产物入库

**目标：** 一个真实图片模型走完整媒体链路。

**位置：** MediaProviderAdapter、DashScopeImageAdapter、AssetIngestService、MediaJobOutputRepository。

**步骤：** 实现C00确认的协议，响应分类为Accepted/Completed/Rejected/Unknown；所有请求有期限；同一job始终用冻结endpoint/model；保存taskId后再进入RUNNING；成功后持久化output槽位，流式下载并保存，再同事务更新asset/job/event。

**安全边界：** 供应商endpoint走现有受控配置校验；结果下载逐跳验证URL与解析IP，不跟到回环、私网、元数据地址；重试仍重新验证；上游返回文件头与扩展名不一致按真实允许类型判断。

**失败处理：** 429按Retry-After退避；POST 5xx/读超时若不能确认拒绝则Unknown；身份错误阻塞并提示修配置；存储失败INGEST_FAILED；保存对象后DB失败复用output key补账，不生成新图片。

**产物：** adapter契约测试、脱敏真实烟测记录、完整下载产物。

**验证／完成：** A18、A19、A20、A21；真实测试必须记录模型、taskId脱敏标识、jobId、资产摘要与费用是否已知。

### C08：取消、对账、事件与恢复入口

**目标：** 状态真实可读，用户能处理失败而不重复生成。

**位置：** MediaJobQueryService、MediaJobActionService、CreativeJobController、事件发布器。

**步骤：** 允许操作由服务端allowedActions计算；queued取消直接终止；running按adapter取消能力处理；查询失败保留任务，达到观察期限标attention；reconcile仅查询上游；普通retry新建子job；recover-result只恢复FETCH。SSE按第2.2节锁序写事件，订阅带鉴权、心跳、游标和reset快照，必要时DB轮询补通知。

**产物：** 详情、动作、SSE和恢复测试。20秒心跳低于现有前端45秒无事件看门狗；心跳无业务seq且不写DB。

**验证／完成：** A22、A23、A24、A25；不得把已取消请求但仍在生成显示成取消成功。

### C09：提示词优化

**目标：** 可选的文本辅助，不改变用户原意、不自动生图。

**位置：** CreativePromptService、Controller；复用DynamicChatModelFactory与chat解析。

**步骤：** 归属校验；限定模式；调用对话模型返回结构化字段；验证字段长度／类型，失败给可读错误；保留原文，前端用户明确采用。模型输出当文本数据，不执行其中工具指令。优化请求有独立request key与短期去重记录，网络重试不静默多次调用；持久化用于审计的原文/结果并限制访问。

**产物：** 优化API、合法/畸形响应测试和前后对比组件契约。

**验证／完成：** A26；优化失败后仍可直接用原提示词生成；供应商无结构化输出保证时本地验证，不声称强保证。

### C10：创作入口与草稿页面

**目标：** 在原Aster外壳中完成项目与输入流程。

**位置：** router/index.js、WorkbenchShell.vue、views/creative/*、components/creative/*、api/creative.js。

**步骤：** 增加入口/项目页/草稿参数区；沿用--wb-*令牌；API复用现有Authorization与X-Aster-Local-Token。现有request为文件局部变量，需要命名导出或抽取共享client并回归原调用，不能假定已可直接import。草稿输入600ms防抖，串行保存，后返回的旧响应不能覆盖新输入；页面离开先完成保存或提示未保存状态。

**产物：** 路由、无配置/空项目/保存中/冲突状态、可选择模型参数。

**验证／完成：** A27；键盘可操作、浅深主题可读；没有后端时Mock页面只能标开发预览。

### C11：任务、结果与素材交互

**目标：** 用户能从输入到生成、下载、再创作完整使用。

**位置：** CreativeStudio、CreativeTasks、CreativeAssets、MediaJobCard/Detail、AssetPreview、useMediaJob。

**步骤：** 首次点击固定幂等键与请求，失败网络重试复用；202后立即显示任务；SSE事件按字符串seq去重、version单调更新，断线取快照后重连，后台tab降频；刷新读现有任务，不提交POST；展示真实allowedActions、保存失败和未知费用；选择“继续创作”另建草稿，原任务不改写。

**性能边界：** 列表分页加载缩略图，不一次下载所有原图；释放组件订阅/定时器和Object URL。取消请求按钮禁连点，但服务端仍须幂等。

**产物：** 可运行完整界面与浏览器证据。

**验证／完成：** A28、A29、A30；刷新／离页后恢复、弱网不重复提交、素材真实可下载。

### C12：超级智能体使用同一服务

**目标：** 对话能发起图片生成，结果进入相同任务和素材库。

**位置：** 新MediaCreationTool、ToolFactory、ToolRiskCatalog、工具确认与结果卡映射。

**步骤：** 注入actor/run/toolCallId，不接受模型编造owner；create/cancel登记EXECUTE，查询与列表READ_ONLY；服务层再次验证权限与限额；调用立即返回jobId，不阻塞工具线程等待生成；前端结果卡订阅任务，智能体结束后媒体任务仍可继续。

**产物：** 工具schema、权限与重放测试、对话结果跳转。重复toolCallId固定相同幂等键，用户另一次需求创建新键。

**验证／完成：** A31；用已有权限模式测试拒绝和允许两条路径，拒绝时供应商调用计数为0。

### C13：工作流外部等待与恢复

**目标：** 长任务让出线程，重启后从原媒体任务继续。

**位置：** WorkflowService拆出节点执行与恢复器；新增workflow_node_execution和run冻结图字段；增量迁移状态约束。

**步骤：** 开始时冻结definitionVersion/graph/input；节点唯一(run,node)；提交mediaJob记录WAITING_EXTERNAL；事件唤醒加轮询兜底；成功后原子写输出并调度后继；失败把对应节点与run标为失败。多个等待分支中任一等待都不能把整个run误判完成。

**恢复边界：** 只保证新媒体节点恢复。对已有http_request等有副作用节点不推断可安全重放；重启发现未知执行结果时停止自动重放并呈现需处理，不把整个工作流宣传为exactly-once。

**产物：** 工作流持久等待状态、恢复器、Mock图测试。

**验证／完成：** A32；执行超过原120秒窗口仍能完成、等待不占执行线程、图更新不影响原run。

### C14：工作流媒体节点与兼容迁移

**目标：** 生图可供下游理解/海报合成使用，不破坏旧图。

**位置：** WorkflowEditor节点目录、参数表单、媒体结果解析、workflow产物展示。

**步骤：** 新增media_image_generate输出结构；image_understand/poster_render支持受权asset引用；定义新的引用选择组件与解析器，明确assets[0].assetId等结构化访问，不硬套旧`node.output`字符串正则；保存版本时验证引用类型；旧image_generate保持原路径，用户显式升级图才走新节点；升级预览显示模型绑定变化。

**产物：** 新节点、兼容测试图、旧字符串引用与新资产引用测试。

**验证／完成：** A33、A34；至少有一条新生图→图片理解/海报链路成功，旧图可载入并维持原规则。

### C15：部署、观测与桌面

**目标：** 可安装、可恢复、可定位问题。

**位置：** 独立creative默认配置、部署说明、桌面打包配置、指标和健康端点。

**步骤：** 配置feature与scheduler开关、并发/文件限制；启动时区分DB、存储和模型配置就绪；注册队列/耗时/不确定任务/入库失败指标；敏感字段脱敏；桌面使用应用数据目录与本机token；停止新提交后允许收尾；备份DB+媒体目录并在隔离目录验证恢复。

**产物：** 运维手册、迁移和回滚步骤、桌面构建产物、故障排查映射。

**验证／完成：** A35、A36、A37、A38；不能通过复制真实密钥到安装包来“开箱即用”。

### C16：图片版验收与交接

**目标：** 按测试标准作出真实发布结论。

**步骤：** 固定验收提交与测试数据；运行A01–A40；真实模型、PostgreSQL、OSS和桌面分别记录；逐条填写PASS/FAIL/BLOCKED/NOT_RUN；汇总构建、截图、日志摘要和缺陷；更新任务台账与context。

**产物：** `docs/test/results/YYYY-MM-DD-ai-creative-p1.md` 和独立归档的脱敏证据。

**验证／完成：** A39、A40与全部P1强制门禁；任何安全/重复费用/数据丢失问题未修复都不能发布。没有完整环境只交付“候选版，待验收”，不能标P1完成。

## 5. P2/P3 实施卡与阶段边界

下列任务已有明确目标与出口；供应商请求字段与FFmpeg实际命令须到实施时依据实际版本确定，不在本文猜测。

### C17：自由画布编辑

依赖C16。新增creative_canvas/canvas_revision（projectId、schemaVersion、version、document），同项目权限与乐观锁。实现Prompt/ReferenceAsset/ImageGeneration/VideoGeneration/Result/Note节点注册、拖拽连线、视口恢复、撤销/重做及保存。复用Vue Flow，业务document不混用workflow图；节点数据只保存资产ID和草稿，禁止base64媒体。

交付画布页面、schema升级器和保存冲突UI。验证B01：刷新布局和引用不丢，冲突能另存，不能跨用户引用。

### C18：画布执行

依赖C17。连线表达参考或提示词上下文；生成前展示执行范围并校验环、悬空引用与能力；按单节点或显式选中子图执行。每次动作冻结输入与引用资产版本，jobId固定回到对应节点，改变画布不改变原job；结果节点指向不可变资产，重跑新增历史。

交付CanvasExecutionService和输入预览。验证B02：编辑在途节点不污染结果归因，重复提交不额外生成，循环在上游调用前拒绝。

### C19：单镜头视频

依赖C16。按C00方式确定一个真实视频模型；扩展video_generation绑定与profile；首版count=1，参数限定该模型真实支持范围。新增视频流式下载、metadata/封面、Range读取；首帧参考图按供应商上传或签名URL方式传输；引用不满足网络可达条件直接提示。

不改变图片状态机的成功语义，扩展deadline/限流与媒体体积；不把音频支持当成默认。交付video adapter、参数界面、历史任务。验证B03/B04：一次真实视频保存播放，重启继续原任务，视频下载有界内存且Range正确。

### C20：P2综合验收

依赖C18/C19。完成“图片生成→画布选图→图生视频（模型支持时）→视频入库下载”；不支持图生视频的所选模型不能通过该门禁，应换用支持模型或明确缩小P2产品范围并更新设计，不能静默跳过。

交付P2结果报告。验证B01–B05和所有受影响P1回归，P1性能与隔离不可退化。

### C21：故事、角色与分镜

依赖C20。新增Story/Episode/Character/Shot领域；LLM拆解产物先作草稿，用户确认才成为版本；角色与场景引用固定素材版本；镜头包含文本、台词、时长意图、模型和选中结果。剧集内顺序唯一，重排事务化，删除不误删共享角色素材。

交付剧情编辑、角色卡与分镜表。验证D01：结构化异常可修正，手工编辑不被再次拆解覆盖，历史版本可追溯。

### C22：分镜批量生产

依赖C21。批次冻结shot版本与参数，批次下每镜头独立job/幂等键；设置最大镜头数、并发和取消范围，明确预计次数和已知费用；失败镜头单独重试，成功镜头不重生成；停止批次只阻止未提交项，上游继续项照实跟踪。

交付batch状态聚合（全成/部分失败/待确认/取消），角色引用和选图逻辑。验证D02：受控5镜头批次中2条故障，修复只提交失败条目；实际验收用Mock避免不必要花费，再小规模真实验证。

### C23：时间线与渲染

依赖C22。定义Track/Clip/Subtitle和时间基准（内部整数毫秒、输出按指定fps量化）；冻结资产版本与timelineVersion。先做顺序拼接、入出点、基础音量和字幕；预览未实现精确效果时标注。受控FFmpeg使用参数数组启动，不把文本拼shell；限制临时目录、并发、CPU/内存、时长与输出大小，清理只限当前render目录。

render_job复用任务可靠性原则但区分本地可重算渲染与有费用生成；渲染失败不能重做镜头生成。输出校验可播放、时长与音画基础同步，再入库。确认所打包FFmpeg构建、许可和平台架构，不假设本机可执行即可以分发。

交付时间线UI、renderer与导出流程。验证D03/D04：确定性本地样片拼接和字幕通过，进程取消/资源超限可收尾，无越界文件写入。

### C24：P3综合验收

依赖C23。完成剧本→分镜→选图/视频→时间线→字幕成片；最少3个镜头、1条字幕轨，包含一次失败恢复。记录模型与素材来源、批次／renderJob与输出摘要；不以演示视频替代数据链路验收。

验证D01–D05及P1/P2关键回归，交付P3独立报告，角色一致性描述为可观察效果，不作绝对保证。

## 6. 交接与变更规范

每次交接必须填写：当前任务ID/状态、基线提交、修改文件、已通过用例及证据路径、真实／Mock范围、未验证事项、下一任务与前置条件。阻塞需写“谁/什么输入能解除”，不能只写“待确认”。

新增bug按AGENTS.md记录；仅文档细化不伪造bug记录。改变状态枚举、API、字段或默认限额时，同步总体设计、实施手册、验收用例和前端文案；不能只改其中一份。

建议每任务一个可评审改动，不自动提交或发布到生产；提交、服务重启、真实收费测试等操作遵循当前会话授权。仓库业务检查采用mvn和前端构建；桌面构建单独验收。没有新增的测试文件/脚本时不声称命令已经存在。

## 7. 后续智能体的起始指令模板

> 阅读 AGENTS.md、最新 docs/context、AI 创作总体设计、实施手册和验收标准。先核对当前源码与台账，执行依赖已满足的 Cxx；明确使用已存在的模型用途绑定，不重复实现。只修改该任务需要的文件，保护本地配置和他人改动。完成对应 A/B/D 验收并保存证据，更新台账与会话摘要；缺少真实环境时标 BLOCKED，继续不依赖它的工作，不把 Mock 当真实通过。
