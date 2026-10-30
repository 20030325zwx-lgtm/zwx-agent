# AI 创作进度复核（2026-10-30，第二次）

## 范围与结论

- 核查 HEAD：`7c681579191bcd591b843b584ac0adb05c6ce6cb`；核查开始时工作区干净。
- 范围：相对 `52e50f1` 的三个新增提交、设计台账、验收记录、相关源码与定向测试。
- P1 图片创作主体已有实现，但未通过总验收，不能认定可发布。P2/P3 未交付。
- 本次只补核查文档，没有修改业务代码、启动/重启服务、执行计费模型调用或运行数据库集成测试。

## 进展

- 调度器默认启用；增加每 30 秒周期恢复及带 epoch 的租约释放。
- 任务来源追踪、越界事件游标 reset、上游不支持取消的反馈、缩略图、本地素材视觉理解已有代码实现。
- 本地存储已增加真实路径检查；上次静态符号链接越界读取/写文件场景的回归测试通过。
- 工作包原台账：DONE 2、IN_REVIEW 14、TODO 9。C16 总验收及 C17–C24 仍 TODO。C05 虽标 DONE，但下述残余问题使该状态不足以代表恢复与并发门禁通过。
- 验收原表：PASS 16、PASS（部分）11、NOT_RUN 12、BLOCKED 1，共 40 项。这是文档状态统计，不是本次重新执行全部验收后的结果。
- VIDEO/NOVEL/COMIC 在创作首页仍为禁用的“规划中”入口，不能算对应能力已经完成。

## 仍未闭环的调度问题

### R1：周期恢复仍可能永久漏掉 SUBMITTING（P1，源码确认，未作进程故障实测）

- `MediaJobScheduler.java:138`：每隔至少 30 秒执行 recover，普通轮次执行 tick。
- `MediaJobRepository.java:218`：普通认领范围包含 SUBMITTING。
- `MediaJobScheduler.java:188`：SUBMITTING 分支不做处理；随后 `:175` 释放租约，将 lease_until 清为 NULL。
- `MediaJobScheduler.java:425`：恢复仅选择 `provider_task_id IS NULL AND lease_until < now()`，不包含 NULL 租约。
- 触发路径：启动时租约尚未过期；租约在两次周期恢复之间到期；普通 tick 先认领该任务并空操作释放，之后 recover 再也选不到它。新增定时恢复没有消除此路径。
- 建议：普通 tick 不吞掉提交中任务，明确处理无租约/过期租约的恢复，并在锁内重新校验恢复条件；补“先启动恢复、再到期 tick、后周期恢复”的测试。

### R2：认领令牌未冻结，旧 worker 仍可能使用新 epoch（P1，源码确认，未作双进程超时实测）

- `MediaJobScheduler.java:155–160`：一次认领整批任务，仅保存 ID。
- `:164–175`：逐个处理前重新读取任务，以当前记录的 epoch 执行和释放，没有验证 lease_owner，也没有保存认领时的 epoch。
- 触发路径：worker A 处理批次前面的慢任务；后面的任务租约到期，被 B 接管；A 随后读取该任务拿到 B 的新 epoch，于是新的 releaseLease 条件仍然成立，可以清除 B 的租约，并可能并行执行查询/入库动作。
- 新增测试 `leaseReleaseIsFencedByEpoch` 只验证“传入旧 epoch 时被拒”，没有覆盖调用者读取并使用新 epoch 的路径。
- 建议：认领事务返回不可变的 ID/epoch/owner 令牌；执行、回写和释放校验该令牌；按处理能力认领并考虑续租，补慢任务跨租约的双 worker 测试。

## 本次实际验证

- 命令：`mvn -q -Dtest=MediaJobStatusTest,ParameterSchemaTest,DashScopeImageAdapterTest,SafeMediaDownloaderTest,MediaJobServiceRulesTest,PromptOptimizationServiceTest,LocalMediaStorageTest,ThumbnailEncoderTest test`。
- 结果：37 项，失败 0、错误 0、跳过 0；退出码 0。包含本地存储 2 项、缩略图 3 项。
- 前端 `npm run build`：退出码 0。
- 临时日志：`/tmp/aster-creative-recheck-tests.log`、`/tmp/aster-creative-recheck-frontend.log`。
- 开发会话记录称全量测试 424 通过、6 跳过；本次未重跑全量，不把该历史数字作为独立复核结果。
- 未覆盖：真实供应商新链路、真实 OSS、浏览器本轮端到端、数据库/多进程故障恢复、桌面安装、备份恢复、负载性能。

## 后续优先级

1. 修复并验证 R1/R2，重新确认 C05 和相关 A10–A13 门禁。
2. 完成 P1 尚缺的参数/权限矩阵、归档并发、取消/重试/SSE/弱网用例及 Agent/工作流端到端（A31–A34）。
3. 按授权范围执行真实生成、提示词优化与 OSS 验证，再补桌面、备份和性能验收。
4. 同步台账与验收证据后再关闭 C16；P2/P3 继续按原依赖推进。

## 文档偏差

原验收结果仍引用 `52e50f1`、已删除的副本文件、414/421 项历史测试数，并称无未修复 P0/P1；与当前 HEAD 和上述源码问题不一致。台账 C06 仍有“缩略图待 C11”的旧描述，C05 所列 `MediaJobRecoveryService` 未在当前源码中找到（实际恢复逻辑在调度器）。后续更新时应区分历史证据与当前验收结论。
