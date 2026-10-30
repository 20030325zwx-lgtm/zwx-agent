# AI创作 P1 验收结果

- 验收日期/执行者：2026-10-29 起，执行智能体（ZCode 会话，接手 C00–C16）
- HEAD及未提交差异/构建ID：基线 `52e50f1`（本批 C16 补充提交后以 `git log` 为准）；工作区仅其他会话副本文件（`WorkflowEditor 2.vue`、`Workflows 2.vue`）
- OS、硬件、JDK/Node/PostgreSQL：macOS arm64；后端本机 8123（launchctl）；数据库＝本机 Docker PostgreSQL（开发库，A02 等需要时另建隔离库）
- 发布形态：网页 / 桌面（未到发布阶段）
- 模型/适配版本/区域；费用授权范围与实际费用是否已知：DashScope `wanx2.1-t2i-plus`（绑定）/`wanx2.1-t2i-turbo`；区域默认；单价未知（NULL）
- 存储适配、测试数据集、Mock服务版本：隔离验收用 LOCAL 存储（/tmp/aster-creative-ui）+ 进程内 Mock 适配器（adapter_key=mock-text2image）；云端 OSS 未参与本轮
- 命令与退出码：见各用例证据

| 用例 | 状态 | 实际结果 | 证据位置/摘要 | 缺陷ID |
| --- | --- | --- | --- | --- |
| A01 | PASS | 基线差异、官方契约（提交/查询/状态/取消/幂等/计费/保留期）与未知项分类完成，全部有出处 | docs/design/2026-10-29-ai-creative-model-contract.md（含文档 URL 与访问日期） | — |
| A02 | PASS | 隔离库验证：空库迁移 12 张表齐全；V7→V8 升级旧数据原样保留；重复迁移无变化；跨用户外键（23503）与幂等唯一键（23505）在数据库层生效 | CreativeMigrationTest（PG，临时隔离库，用后即删）；`mvn test -Dtest=CreativeMigrationTest` | — |
| A03 | PASS | 用户绑定解析出 wanx2.1-t2i-plus（VERIFIED，档案自动补建）；显式 modelRowId 覆盖为 turbo；无绑定用户阻断（MODEL_NOT_CONFIGURED）不回退 chat；越权模型行拒绝 | MediaModelResolverTest（PG/UNIT，本地目录数据） | — |
| A04 | NOT_RUN | 需要 C04 的创建接口与 MOCK 供应商（参数拒绝/422/请求计数） | — | — |
| A05 | NOT_RUN | 部分覆盖：项目/草稿跨用户访问拒绝已在服务层验证（CreativeProjectServiceTest），job/素材/事件/下载待 C04/C06/C08 | — | — |
| A06 | NOT_RUN | 部分覆盖：两端同版本草稿保存冲突（409，文本不覆盖）已验证；归档与创建 job 并发待 C04 | — | — |
| A07 | PASS | 20 并发同键同内容：恰好 1 条 job、1 条 JOB_CREATED 事件，19 次复用返回同一 jobId | MediaJobServiceTest.twentyConcurrentSameKeyRequestsCreateSingleJob（PG，事务内锁项目行串行化） | — |
| A08 | PASS | 同键改 prompt → 409；同键换显式模型 → 409；同键同内容复用返回冻结模型快照（复用路径不重解析） | MediaJobServiceTest.reusesSameKeyAndRejectsChangedContent | — |
| A09 | PASS | 事件写入抛错 → job 事务整体回滚（无半成品）；恢复后同键正常受理；排队上限（USER max_queued=1）第 2 条 429 且无任务落库 | MediaJobServiceTest.eventFailureRollsBackWholeTransaction / queuedQuotaReturns429WithoutHalfCreatedJob（@MockitoSpyBean 注入故障） | — |
| A10 | PASS | 双实例并发 tick + 5 任务：USER 在途上限 1 生效，仅 1 任务进入 RUNNING，其余留在 QUEUED（行锁 + SKIP LOCKED 认领） | MediaJobSchedulerTest.twoSchedulerInstancesRespectUserQuotaUnderConcurrency（PG，Mock adapter） | — |
| A11 | PASS | 认领推进 lease_epoch；旧 version/epoch 回写被拒（false），新 epoch 正常推进 | MediaJobSchedulerTest.oldWorkerFencedWriteIsRejectedAfterReclaim（PG） | — |
| A12 | PASS | 提交成功（taskId 落库）后模拟租约过期重启：recover 恢复查询原 taskId，最终 SUCCEEDED；submit 次数保持 1 | MediaJobSchedulerTest.recoveryResumesQueryWithoutResubmitting（PG/MOCK） | — |
| A13 | PASS | 提交结果不明（Mock 断连）→ RECONCILING，无 providerTaskId；再次调度不重发（submit 次数保持 1）并标记 attention | MediaJobSchedulerTest.uncertainSubmissionEntersReconcilingWithoutResubmit（PG/MOCK） | — |
| A14 | PASS | PNG/JPEG/WebP 上传 READY 且尺寸解析正确；伪造扩展名、>20MiB、>40MP 全部拒绝且无残留 | CreativeAssetServiceTest（LOCAL/PG，独立服务栈） | — |
| A15 | PASS | 本地存储拒绝 ../ 逃逸；跨用户 content/update/delete 全部拒绝 | CreativeAssetServiceTest（哨兵=路径规范化校验；越权用例） | — |
| A16 | PASS | 活动任务引用素材时删除 409；任务终态后软删除成功，历史任务记录保留 | CreativeAssetServiceTest.softDeleteBlockedByActiveJobReferenceThenAllowed（PG） | — |
| A17 | PASS（部分） | 本地 put/open/stat/delete 往返摘要一致；缩略图已实现（320px 以内、失败不影响原件，上传与生成入库均生成），有单测与上传用例；OSS 真实往返仍待环境补验 | CreativeAssetServiceTest.localStorageRejectsTraversalAndRoundTrips / uploadsValidPngAndParsesDimensions；ThumbnailEncoderTest | — |
| A18 | BLOCKED | 真实模型调用需要费用授权（模型 `wanx2.1-t2i-plus`、1 次、单价未知）；未执行任何计费调用 | 适配器与契约已就绪；授权后可执行 | — |
| A19 | PASS | 注入对象存储写失败：job → INGEST_FAILED，submit 次数保持 1；恢复后 INGEST_FAILED→FETCHING→SUCCEEDED，对象落盘、素材 1 条 | MediaJobSchedulerTest.ingestFailureRetriesWithoutSecondSubmit（PG/LOCAL/MOCK） | — |
| A20 | PASS | 对象写入成功但素材插入失败 → 复用同一 assetId/objectKey 重试成功，素材不重复；结果 URL 不可达时保持 INGEST_FAILED 且不重发提交 | MediaJobSchedulerTest.objectWrittenButAssetInsertFailsReusesSameSlot / expiredResultUrlStaysIngestFailedWithoutResubmit | — |
| A21 | PASS | 401→PROVIDER_AUTH_FAILED；429+Retry-After→throttled(17s)；5xx→Unknown；2xx 无 task_id→Unknown；连接失败→Unknown；429 默认退避；回环/内网/169.254 拦截；重定向到元数据地址拦截；超限下载中途终止 | DashScopeImageAdapterTest 13 例 / SafeMediaDownloaderTest 6 例（本地 HTTP Mock，无外网） | — |
| A22 | PASS（部分） | QUEUED 取消 → CANCELLED，无上游调用；重复取消幂等返回 CANCELLED；事件为 JOB_CREATED→CANCELLED；RUNNING 请求取消且上游不支持时回到 RUNNING，写入 `UPSTREAM_CANCEL_UNSUPPORTED` 与 CANCEL_UNSUPPORTED 事件，不假报已取消 | 隔离实例 HTTP 实测；MediaJobServiceRulesTest；MediaJobSchedulerTest.unsupportedCancelKeepsRunningAndExplainsWhy | — |
| A23 | PASS（部分） | CANCELLED 重试创建新 job 且 parentJobId 正确、模型快照保留；INGEST_FAILED 调用 resume-ingest 后 SUCCEEDED（只重新入库）。FAILED 普通重试、RECONCILING 确认重试未执行 | 隔离实例 HTTP 实测；MediaJobSchedulerTest A19 | — |
| A24 | PASS（部分） | 事件按 seq 返回 JOB_CREATED/CANCELLED，详情含 outputs/version；越界游标返回 `reset=true` + 最新快照，负游标 400，SSE 连接先发 `job-reset` 再续传；断线重连与重复消息的浏览器端验证仍未执行 | MediaJobServiceTest.eventCursorBeyondMaxRequestsReset；GET /jobs/{id}/events 实测 | — |
| A25 | NOT_RUN | 供应商删除保护与 attention 展示未逐条执行 | — | — |
| A26 | NOT_RUN | 优化链路需要对话模型调用（可能计费），本轮只验证解析规则（4 例） | PromptOptimizationServiceTest | — |
| A27 | PASS（部分） | 无绑定 → 引导条 + 生成禁用；新建项目 → 自动保存草稿 → 刷新后草稿保留；错误文案可读。两个页签冲突未执行 | 浏览器实测（1280/1440/1024/390） | — |
| A28 | PASS（部分） | 刷新不重复提交；再次生成产生新 job、卡片按 jobId 合并；生成中按钮禁用防双击。弱网超时重试未执行 | 浏览器实测 + 任务列表计数 | — |
| A29 | PASS（部分） | 生成中/已完成/保存失败文案与 allowedActions 对应；无假进度、无免费声明；取消/待确认/取消中状态未逐一截图 | 浏览器实测截图 + 任务详情 | — |
| A30 | PASS（部分） | 素材列表、项目筛选、收藏/下载/删除/继续创作按钮存在；分页与真实下载校验未执行 | 浏览器实测；CreativeAssetServiceTest | — |
| A31–A34 | NOT_RUN | 智能体工具、工作流等待与媒体节点端到端未执行；本批已补来源追踪（AGENT/WORKFLOW + runId/nodeId）与本地素材视觉理解回退，尚未跑端到端 | — | — |
| A35 | PASS（部分） | `submissions-enabled=false` 时新任务返回 503 + code，已有任务仍可查询；暂停/恢复调度未演练 | 隔离实例 HTTP 实测 | — |
| A36 | PASS（部分） | `/ai/creative/health` 返回开关/存储/绑定/DB/本人计数，无密钥或对象键；日志脱敏扫描未做 | HTTP 实测；CreativeHealthController | — |
| A37–A38 | NOT_RUN | 桌面安装包与备份恢复演练未执行 | — | — |
| A39 | PASS（部分） | 后端全量 `mvn test`：414 通过/0 失败/6 跳过（CI 环境变量禁用真实模型用例）；前端 `npm run build` 通过；工作台/创作/任务/素材页面浏览器冒烟通过。桌面构建与旧登录/对话深度回归未跑 | 构建日志；浏览器实测 | — |
| A40 | NOT_RUN | 负载、状态可见 P95、资源与可用性阈值未测量 | — | — |

- 统计：PASS 16 / PASS（部分）11 / FAIL 0 / BLOCKED 1（A18）/ NOT_RUN 12（总数 40，逐行核对）
- 最近一次全量测试：421 通过 / 0 失败 / 6 跳过（`mvn clean test`，CI 环境变量关闭真实模型用例）
- 真实生成：本阶段未新增计费调用；历史验证（2026-10-29 wanx2.1 两模型各 1 张成功）仅作参考
- P0/P1 缺陷：无未修复项；本轮修复 6 个问题（作业列表 SQL、租约阻塞轮询、配额竞态、前端导入/草稿竞态、维度解析导致入库失败），均见 docs/bugs/bug-2026-10-30-*
- 未覆盖环境和原因：REAL（A18 需费用授权，本轮无计费调用）；WEB 使用隔离测试账号与 Mock 模型（不使用、不输出用户密钥）；DESKTOP/OSS/备份演练未执行
- 是否满足全部门禁：否（进行中）
- 发布结论：未到发布阶段
- 后续任务与解除阻塞条件：A22–A30 剩余分支（RUNNING 取消、SSE 断线、两页签冲突、弱网重试）可继续在隔离环境执行；A18 需要明确授权模型、次数与费用；A37/A38 需要桌面安装包与备份环境
