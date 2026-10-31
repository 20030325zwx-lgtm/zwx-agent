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

## 追加 2（2026-10-31 傍晚）：淘宝服饰海报工作流全链路跑通（image_edit 能力）

- **新能力**：创作操作 `image_edit`（参考图编辑）。MediaJobService BR08 按操作区分
  （text_to_image 仍拒参考图；image_edit 需 1–3 张本人 READY 素材，受理时校验并同事务登记
  media_job_asset_ref role=input）；requestHash 纳入参考图 ID；resolver 按 operation 校验档案能力，
  openai-compatible 档案 operations={text_to_image,image_edit}；适配器接口加 4 参 submit 重载
  （参考图字节由调度器从素材库读出传入），OpenAiImagesMediaProviderAdapter 走 multipart
  POST /v1/images/edits（image[] 多文件）；工作流新节点 `media_image_edit`（imageAssetIds 支持数组
  或逗号分隔、可引用 {{节点.output.assetId}}），复用 WAITING_EXTERNAL 等待恢复链路。
- **模板**：taobao-apparel-poster「淘宝服饰海报（模特试穿）」：
  start(模特/上衣/裤子素材ID + 卖点关键词) → 模特理解 → 试穿提示词(text_template) →
  模特试穿(media_image_edit) → 海报文案(llm_text) → 海报合成(poster_render)。
- **E2E 结果（运行 c1e9e60a）**：6 步全部 SUCCEEDED；中转站生成素材 3 张（模特/上衣/裤子，
  low quality）→ 试穿图（同一模特换装，SUCCEEDED ~90s）→ LLM 文案 JSON → 1200×1600 海报
  （标题「加绒显瘦秋冬装」+ 副标题 + 3 卖点 + 暖色配色）；试穿图与海报均自动归入项目素材库。
- **计费记录**：本日中转站出图 5 张（edits 探测 1 low + 素材 3 low + 试穿 1 默认档）。
- 已知边界：海报版式固定（单面板 1200×1600）；试穿保真度依赖中转站模型；创作台 UI 暂无
  image_edit 表单（工作流/Agent 链路可用）；素材上传 >1MB 的 base64 无法经浏览器 evaluate
  中转（工具限制），演示用 sips 转 JPEG。
- 未提交：image_edit 能力 + 工作流节点 + 测试待提交。

## 追加 3（2026-10-31 晚）：工作流编辑器素材上传与交互修复

- **节点设置面板可关闭**：面板加 × 按钮；VueFlow @pane-click 点画布空白即取消选中。
- **素材参数控件**（新组件 components/workflow/AssetParamField.vue）：节点面板中
  imageAssetId/imageAssetIds 等素材参数渲染为「项目选择 + 素材选择 + 上传图片」控件
  （imageAssetIds 多选 chips）；值为 {{...}} 引用表达式时显示表达式与"改为手动选择"切换。
- **运行对话框**：编辑器"运行"改为弹窗，自动从图参数扫描运行输入
  （{{input.KEY}} 与 {{start.output.KEY}}），*AssetId* 键渲染素材控件、其余为文本框；
  增加目标创作项目选择（运行绑定 + 产物归属）；startWorkflow 带 input map + projectId。
- **修复 graphDirty 误判**：markDirtyOnChange 过滤 dimensions/select 事件，
  此前画布刚加载就被当成"未保存修改"而阻止运行。
- 验证：面板开/关（× 与空白点击）、运行对话框 3 个素材字段自动识别、素材下拉列出项目素材、
  节点面板模板表达式降级提示。文件上传走真实 file input（用户浏览器可用；ZCode 内嵌浏览器
  无法自动化文件选择，未自动化验证）。
- 未提交：编辑器改动 + AssetParamField 组件。

## 追加 4（2026-10-31 晚）：素材库来源分组与大图预览

- **后端**：AssetView 增加 origin 字段；/ai/creative/assets 支持 origin 过滤
  （UPLOAD=原始素材（上传），GENERATED=生成结果（任务产物/工作流海报））。
- **前端**：素材库页与项目素材库 tab 均增加「全部 / 原始素材 / 生成结果」筛选 chips +
  卡片来源徽标 + 点击大图预览（新组件 views/creative/AssetPreview.vue：大图、尺寸/大小/时间、
  收藏/继续创作/删除/下载，删除后自动关闭）。
- 过程 bug：一次 python 批量补丁误删了 CreativeAssets.vue 的 starred ref 定义导致列表报错，
  已修复并让 load 错误透出真实 message。
- 验证：素材库 全部 7 / 生成结果 2 / 原始素材 5，徽标正确，预览打开正常；
  项目 tab 筛选生成结果=2（试穿图 + 海报）。
- 未提交：素材分组与预览改动。

## 追加 5（2026-10-31 晚）：专家联合评审第一批修复

- 专家评审落库：docs/design/2026-10-31-workflow-creative-expert-review.md（P1×5/P2×2 + 架构/规范/产品/UI 建议）。
- 本轮修复（详见 docs/design/2026-10-31-workflow-creative-expert-review-response.md）：
  R02 恢复器 SQL 筛终态（队首阻塞，事务级验证 20+1）；R01 advance/fail 同事务 + 孤儿任务重链 + 运行态修复；
  R04 image_edit 结果「再做一版（同参考）」保留语义（详情接口补 referenceAssetIds）；
  R05 海报单次存储（mediaStorage）+ registerRendered（origin=DERIVED + metadata）+ Artifact 带 assetId + 产物 asset: blob 渲染；
  R07 档案解析只创建不覆盖 + V12 回填 image_edit；
  R03 部分：workflowInputs.js 共享推导，创作首页弹窗渲染同一运行表单（3 素材字段）。
- 连带修正：素材"生成结果"筛选语义含 DERIVED；徽标按 UPLOAD 判断；MediaModelResolverTest 改绑定派生断言
  （管理员绑定已切到中转站模型，硬编码 wanx 属环境性假设）；评审材料 §7 表述修正。
- 后续批次：R06、R03 完整版（schema/预检/幂等键）、调度器有界执行池、WAITING_USER、三版式、模板导出导入。

## 追加 6（2026-10-31 晚）：模板 v2 多姿势多角度 + 失败范围语义

- **模板 v2**（taobao-apparel-poster，运行绑定最新版）：单机位 → 三机位（正面全身 / 侧面四分之三 / 背面全身），
  每个机位一个 media_image_edit 节点，提示词 = 基础试穿约束(text_template) + 各自姿势与角度指令；
  海报使用正面机位产物，全部姿势图归入素材库（可用于详情页多角度展示）。
- **失败范围语义**（联合评审 §3"失败范围"）：failMediaNode 按依赖图计算传递闭包——失败节点
  无下游依赖时仅标记该节点失败、其余分支继续、运行仍可 SUCCEEDED（steps 内保留 FAILED 步骤诚实可见）；
  有下游依赖才跳过依赖并判定运行失败。execute() 续跑跳过 FAILED 节点（不自动重跑，重试走任务级"再做一版"）。
- **实测运行 55dcae2b**：侧面机位提交遇中转站瞬时故障（SUBMIT UNKNOWN → 观察窗口 10 分钟后诚实 FAILED），
  失败范围语义生效——正面/背面机位成功、海报合成，运行整体 SUCCEEDED（steps 含 1 个 FAILED 步骤可见）。
  计费：成功 2 张 + 失败 1 次（费用未知已如实标注）。
- 待办（下一批）：侧面机位这类瞬时故障的自动重试策略（当前诚实失败，用户可整跑重试）；三机位可配置化。

## 追加 7（2026-10-31 晚）：桌面端版本对齐

- `desktop/` 全量重打包：dist:mac 产出 Aster-0.1.2-arm64.dmg/zip（release/，已 gitignore）。
  已验证包内 jar 含 OpenAiImagesMediaProviderAdapter 与 V12 迁移，前端 asar 为最新构建（含多姿势模板/素材分组/徽标）。
- 桌面端数据与网页端隔离（自带 pgserver，userData/pgdata，库 aster）：中转站供应商需在桌面端
  「模型设置」重新配置一次（拉取模型 → 勾选图片生成 → 绑定用途）；desktop profile 已有
  allow-local-model-endpoints=true，VPN fake-IP 放行在桌面端同样生效。
- 安装：退出正在运行的旧版 Aster → 打开 DMG 拖入 Applications 覆盖 → 启动（自有库会增量跑 V1–V12 迁移）。

## 追加 8（2026-10-31 晚）：项目创作台内的工作流创作入口

- 用户反馈"创作台里看不到怎么用工作流"：项目详情创作 tab 右侧面板新增「用工作流创作」——
  弹窗内选模板（自动加载定义并推导输入字段：图片类用 AssetParamField 上传/选择，defaultProjectId
  默认当前项目）、填关键词，目标项目固定为当前项目，产物直接归入。
- AssetParamField 增加 defaultProjectId 属性；修复 CreativeProject 缺 reactive 导入导致的白屏。
- 验证：弹窗模板选择 → 3 个素材字段自动出现（默认项目=当前项目），构建通过。

## 追加 9（2026-10-31 深夜）：AI 小说创作 v1（有状态的长篇创作层）

- **概念定位**（回应用户"工作流运行 vs 用工作流创作有何区别"）：底层同一引擎同一接口，入口差异只有项目绑定+表单；纯图片画布直跑即可。真正的分界 = 创作层持有跨运行状态（设定/章节/素材），工作流是读状态→生成→写回的单次管线。详见 `docs/design/2026-10-31-ai-novel-creation.md`。
- **V13**：`novel_chapter`（组合外键归属、章号唯一、source_run_id 幂等）+ `creative_project.settings_json`（{characters, worldview, style}）+ 种子模板 novel-chapter v1（context→正文(system)→概要→save）。
- **引擎**：新节点 `novel_context`（设定+目录+近N章概要→文本块）/`novel_chapter_save`（按 run 幂等入库）；`MAX_OUTPUT_CHARS` 4000→100_000（跨等待/续跑时长文本不再截断，R06 输出截断部分修复）；llm_text 增可选 system 参数。
- **后端**：`creative/novel`（NovelChapterService/NovelPlanningService）+ NovelController（chapters CRUD+confirm、settings、plot-suggestions 走 PURPOSE_CHAT）；CreativeKind.enabled() 放行 NOVEL；CreativeProjectService.settingsOf/updateSettings。
- **前端**：小说项目页「章节」tab（默认，替代图片创作 tab）：章节列表/展开编辑/确认入库/删草稿 + 作品设定折叠区 + 「写新章节」弹窗（AI 建议 3 组候选点击采纳→发起运行）；创作首页开放小说 chip。
- **验证**：NovelRulesTest 4 项全绿；浏览器 E2E 双章——第1章「星坠前夜」1753 字（上下文报"既往 0 章"）→ 确认入库 → 第2章 AI 建议明显承接第1章概要、运行报"**既往 1 章，本章为第 2 章**"、草稿 1999 字正文衔接所采走向。V13 迁移核对、前后端构建通过。
- 未做（需用户启动）：漫剧、连写多章、结构化角色卡、章节版本历史；桌面端随下次打包携带（迁移自动增量跑）。

## 追加 10（2026-10-31 深夜）：画布契约与创作闭环第一批（Infinite Canvas 评审）

- 评审文档：`docs/design/2026-10-31-infinite-canvas-comparison-and-refactoring.md`；本批记录：`docs/design/2026-10-31-canvas-contract-loop-batch1.md`。
- **节点契约**：新 `frontend/src/views/workflow/nodeCatalog.js` 单一注册表（14 类型：label/分组/默认参数/输入输出 kind/必填/帮助），节点库、表单、连线绑定、预检全由它驱动。**连线即绑定**：kind 兼容自动写绑定表达式（text→`{{源.output}}`、asset→`{{源.output.assetId}}`、imageAssetIds 追加上限 3），边显示参数名 label；旧图零迁移（applyGraph 从 params 反推 label）；不兼容拒绝连线。
- **运行前预检**：前端 validateGraphForRun（运行对话框错误列表+定位）；后端 REQUIRED_PARAMS+missingRequired 纯函数（保存/发起共用，5 项单测 WorkflowGraphValidationTest）——语义：外层约束 AND、组内备选 OR。
- **状态边界**：撤销/重做（快照栈 50、600ms 合并、Ctrl+Z/Shift+Z）；顶栏「未保存」徽标；运行面板明示「运行使用发起时的定义快照」+详情显示快照版本。
- **闭环 B1**：V14 种子 `dual-candidate-poster`（双分支候选）与 `selected-poster`（理解→文案→海报）；素材库「对比」选择模式 + AssetCompare 并排选定 + 「用此图合成海报」发起（keyword 可空）。
- **布局**：编辑器重排为 左节点库(可收起)/中大画布/右参数(按需)/底运行(可收起)。
- **验证**：契约单测 5 项绿；E2E——旧图兼容、撤销重做、连线自动绑定（toast+边 label+参数写入）、类型拒绝、未保存拦截、双候选运行(2 张计费)→对比选定→合成 SUCCEEDED→海报 DERIVED/READY/1200x1600 归档。临时工作流 contract-e2e-temp 已删。
- **取舍**：撤销用快照栈（命令化随 C 批）；预检只查引用存在（上游关系二期）；双候选用两节点（单节点多张留 C 批）。未做：C（局部重跑/输入快照）、D（Agent 画布工具/模板导入导出）、WAITING_USER、role 化引用、性能基线。

## 追加 11（2026-10-31 深夜）：运行输入业务化 + C/D 批（用户指出 {{input.keyword}} 暴露不合理）

- **输入声明 schema**（R03 完整版落地，方案详见 `docs/design/2026-10-31-run-form-schema-cd-batch.md`）：
  V15 `inputs_json` + 种子模板补声明；`buildRunForm(definition)`（声明优先/扫描兜底）统一三入口表单——
  运行者只见中文 label/必填星号/占位提示，asset 字段渲染 AssetParamField；编辑器 inspector 可增删改输入字段、
  「插入引用」chips 把语法降级为点击插入；start() 用 missingDeclaredInputs 报业务化缺失错误。
- **C 批 · 从节点重跑**：computeRerunSteps 纯函数（祖先继承复用产物不重复计费/未成功祖先回退重跑/下游重执行/
  平行分支 SKIPPED）+ rerunFrom（复用父 input/定义快照/项目绑定，artifacts 全新）+ 运行面板「从此步重跑」。
- **D 批 · 模板导出导入**：export（脱敏 bundle）/import（冲突自动改名 {key}-import-xxxx，响应带最终 key）；
  Workflows 列表页导出/导入入口。
- 单测 11 项全绿（抓出祖先继承 SKIPPED 误标、key 正则拒驼峰两个真 bug）；E2E：业务化运行对话框
  「商品关键词*」、inspector 编辑器、chip 插入、从「文案策划」重跑 SUCCEEDED（近 10 分钟 media_job=0 零生图）、
  两次导入分别得 -import-0ab8/-import-3a89。
- 未做：D-2 Agent 结构化画布工具（单独一批）、输入默认值/枚举、role 化引用、项目页重跑入口。

## 环境注意事项
- 未读取/修改 application-local.yml；启动/重启按 AGENTS.md launchctl 流程执行。
- 本地库存在两个挂 wanx 模型的供应商（官方+UI Mock），MediaModelResolverTest 辅助查询已改为固定取 id 最小的官方供应商。
