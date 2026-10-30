# AI创作 P1 验收结果

- 验收日期/执行者：2026-10-29 起，执行智能体（ZCode 会话，接手 C00–C16）
- HEAD及未提交差异/构建ID：基线 `94a53e8`；差异＝docs（设计/实施/验收/会话/索引）未提交 + 其他会话副本文件（`WorkflowEditor 2.vue`、`Workflows 2.vue`）
- OS、硬件、JDK/Node/PostgreSQL：macOS arm64；后端本机 8123（launchctl）；数据库＝本机 Docker PostgreSQL（开发库，A02 等需要时另建隔离库）
- 发布形态：网页 / 桌面（未到发布阶段）
- 模型/适配版本/区域；费用授权范围与实际费用是否已知：DashScope `wanx2.1-t2i-plus`（绑定）/`wanx2.1-t2i-turbo`；区域默认；单价未知（NULL）
- 存储适配、测试数据集、Mock服务版本：尚未实现（C06/C05）
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
| A17 | PASS（部分） | 本地 put/open/stat/delete 往返摘要一致；OSS 适配器已实现（复用 OssClientProvider），真实 OSS 往返在 C16 用真实环境补验；缩略图待 C11 | CreativeAssetServiceTest.localStorageRejectsTraversalAndRoundTrips | — |
| A18–A40 | NOT_RUN | 尚未执行 | — | — |

- 统计：PASS 13（含 1 项部分覆盖）/ FAIL 0 / BLOCKED 0 / NOT_RUN 27（总数 40）
- 真实生成：本阶段未新增计费调用；历史验证（2026-10-29 wanx2.1 两模型各 1 张成功）仅作参考
- P0/P1 缺陷：无
- 未覆盖环境和原因：REAL/STORE/DESKTOP/MOCK 环境随 C05–C07 建立
- 是否满足全部门禁：否（进行中）
- 发布结论：未到发布阶段
- 后续任务与解除阻塞条件：C01 数据契约（无外部依赖）
