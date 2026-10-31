# 工作流并入 AI 创作 + 中转站生图接入会话（2026-10-31）

## 改动内容

### 阶段 0：调度 R1/R2 修复（2026-10-30 复核报告的两个 P1）
- R2：`MediaJobRepository.claim` 改 `RETURNING` 返回冻结 `ClaimToken`；`MediaJobScheduler.tick` 携带令牌处理与释放，不再重读 epoch。
- R1：`findDueForClaim` 排除 SUBMITTING；`recover()` 覆盖 NULL/过期租约的 SUBMITTING，锁内重校验，按有无 taskId 分流 RUNNING/RECONCILING。
- 新增同步适配器基建：`syncSubmitTimeoutSeconds()`、`extendLeaseFenced`（提交前延长租约）、`reconcileUncertainFenced` + `observation_deadline_at`（观察窗口后如实 FAILED）。
- bug 记录：`docs/bugs/bug-2026-10-31-scheduler-r1-submitting-recovery.txt`、`bug-2026-10-31-scheduler-r2-claim-token.txt`。

### 阶段 1：工作流并入 AI 创作（承接原 C17/C18，2026-10-30 用户决策）
- `V11__workflow_run_project.sql`：`workflow_run.creative_project_id`（可空外键+索引）。
- `WorkflowService.start` 支持 `projectId`（校验归属）；`startMediaImageTask` 改 7 参 create（sourceType=WORKFLOW + runId + nodeKey，修正此前记为 UI 的偏差）；项目归属优先级=运行绑定>节点参数>兜底。
- `GET /ai/workflows/runs?projectId=`；poster_render 产物在绑定项目时归集进项目素材库（尽力而为）。
- 前端：CreativeHome 工作流创作入口+弹窗（选工作流/项目/关键词→发起→跳项目页）；CreativeProject 新增「工作流」tab（运行列表/步骤/产物预览/轮询）；WorkflowEditor 节点帮助文案更新。

### 阶段 2：中转站生图（OpenAI images 兼容 + 拉取模型列表）
- 新增 `OpenAiImagesMediaProviderAdapter`（key=openai-images，同步 /v1/images/generations，b64_json/url 双解析，不发 response_format，A21 对齐错误分类，`1024*1024`→`1024x1024`）。
- `MediaModelResolver.ensureProfile` 协议映射泛化：openai-compatible→openai-images（UNVERIFIED）；`ParameterSchema.openAiImages()`（size 自由 WxH）。
- `POST /settings/providers/{id}/fetch-models`：个人 openai-compatible 供应商拉取 /v1/models 增量入库（默认停用）；`ModelCatalogSettings.vue` 拉取按钮 + 模型能力可编辑（新增图片生成选项）。

### 文档
- 新设计文档 `docs/design/2026-10-31-workflow-creative-integration.md`；台账 C05 补修复证据、C17/C18 标"已承接"；运维手册补 3 个新配置项。

## 验证结果
- 定向测试全绿：13 个测试类共 73 项 0 失败（MediaJobSchedulerTest 12 含 R1/R2 时序新用例、OpenAiImagesMediaProviderAdapterTest 10、MediaModelResolverTest 5、ModelCatalogFetchModelsTest 2、CreativeMigrationTest 7 含 V11，以及复核基线 8 类 37 项回归）。
- 前端 `npm run build` 通过；`mvn -DskipTests package` 通过；launchctl 重启后 `/api/health` 返回 ok，V11 迁移已在库中应用。
- 浏览器端到端（无计费链路）：创作首页「工作流创作」入口 → 弹窗选商品海报 + 项目「秋季新品视觉」→ 发起运行 → 项目页「工作流」tab 显示运行（5 步全部完成）→ 海报产物带签名 URL → **海报自动归集进项目素材库**（877KB PNG，含缩略图）。
- 未做：真实中转站计费出图（需用户提供 base_url+key，同 A18 约束）、真实 DashScope 生图端到端（计费）。

## 遗留事项
- 创作台 size 选择器仍为 DashScope 固定枚举，中转站模型自由尺寸需后续读 profile schema 渲染。
- C16 总验收（A31–A34 等）仍待执行；P2/P3 视频链路未动。
- fetch-models 不做模型类型启发式分类，能力由用户勾选。
- 测试环境注意：调度相关测试 SQL 禁用 DB `now() ± interval`，必须绑定 JVM 时间戳（Docker VM 时钟漂移会造成跨时钟比较偶发失败，本批已修复测试中的实例）。

## 追加（2026-10-31 下午）：真实中转站验证通过 + 两处修复

- **真实中转站 E2E 通过（含计费一次）**：用户提供 KKAI 中转站（https://api.kkrich.ltd/v1）。
  UI 链路全通：新建供应商 → 拉取模型列表（gpt-image-2 / 2.5-flare / 2.5-sunburst）→
  启用 gpt-image-2.5-sunburst 并勾选"图片生成" → 绑定用途 → 创作台真实出图
  **SUCCEEDED**（约 90 秒，b64 返回直接入库，产物 1.5MB 已入项目素材库）。
  注意：该绑定替换了用户原来的 DashScope wanx2.1-t2i-plus 用户绑定，可在模型设置一键改回。
- **修复 1（bug-2026-10-31-vpn-fakeip）**：用户 VPN 把中转站域名解析成 fake-IP
  （fdfe:dcba:9876::fb 落在 ULA 段）被模型地址公网校验误拦。本机部署模式下主机名
  形式地址跳过 DNS 公网校验（ModelCatalogService.isHostnameEndpoint），application-local.yml
  经用户同意加 allow-local-model-endpoints: true。IP 字面量校验不放宽。
- **修复 2**：创作台固定发送 watermark 参数被 openAiImages schema 按 BR07 拒绝
  （提交按钮点了没反应，后端 4xx）。BindingView 增加 protocol 字段，前端仅对
  dashscope 协议发送/展示水印参数；Agent 工具与工作流节点本就只发 size，无需改。
- target/classes 曾残留 "Name N.class" 垃圾文件导致打包失败，`mvn clean package` 解决。
- 未提交：以上修复与 ChatRoom 去头像改动待用户确认后提交。

## 环境注意事项
- 未读取/修改 application-local.yml；启动/重启按 AGENTS.md launchctl 流程执行。
- 本地库存在两个挂 wanx 模型的供应商（官方+UI Mock），MediaModelResolverTest 辅助查询已改为固定取 id 最小的官方供应商。
