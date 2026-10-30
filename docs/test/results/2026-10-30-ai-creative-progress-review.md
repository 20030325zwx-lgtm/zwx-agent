# AI 创作模块进度核查（2026-10-30）

基线：HEAD 52e50f1。范围：近期提交、实施台账、原验收报告、关键源码抽查、无数据库/无付费定向测试与前端构建。不是全量安全审计或 P1 最终验收。

## 进度结论

- 94a53e8 后新增三个提交：670e66b 底座、cfd8107 供应商/任务/界面/工作流、52e50f1 创作类型通用化。
- 台账 C00–C15 均已有实现记录：2 项 DONE（C00/C05），14 项 IN_REVIEW；C16 总验收 TODO，C17–C24 TODO。
- 已有项目/草稿、图片模型解析、持久任务与幂等、存储与素材、DashScope adapter、任务API、创作界面、提示词优化、智能体工具、工作流等待、开关和健康信息。
- VIDEO/NOVEL/COMIC 为项目类型预留：前端创建选项仍禁用并写“规划中”，不能视为已有视频、小说、漫画生成。自由创作画布、短剧与剪辑尚未实施。
- A01–A40 原验收表按行展开为 PASS 16、PASS（部分）11、NOT_RUN 12、BLOCKED 1；报告汇总写成17/10，需纠正。以上是历史记录状态，并非本轮重新验证全部通过。原规范没有“部分通过即可签收”，部分覆盖不能算完成。

## 本轮实际验证

命令：mvn -q -Dtest=MediaJobStatusTest,ParameterSchemaTest,DashScopeImageAdapterTest,SafeMediaDownloaderTest,MediaJobServiceRulesTest,PromptOptimizationServiceTest test

- 32 tests，0 failures，0 errors，0 skipped。读取本轮 target/surefire-reports XML 汇总。
- frontend 下 npm run build：成功，14.40s；存在大chunk与stream浏览器外部化警告，不作为本轮失败。
- git diff --check：通过。
- 无真实模型调用，无数据库测试，无服务重启，无桌面包验收。本轮没有复跑历史会话所报415项全量测试。

## 需要优先修复的问题

### R1 [P1] 快速重启可能让 SUBMITTING 永久停留

位置：creative/job/MediaJobScheduler.java:117、174、402。

recover() 仅在 @PostConstruct 启动时调用，且只选择 lease_until < now() 的 SUBMITTING。若提交中崩溃后在60秒租约到期前重启，启动恢复看不到该任务；后续tick虽然会认领它，但SUBMITTING分支不执行任何恢复操作，finally又清空租约。没有周期恢复，且 lease_until NULL 也不匹配恢复查询。用户可一直看到提交中、在途槽位仍占用。

建议：周期性扫描过期/无租约的提交状态，锁行后二次检查所有权，未知提交进入RECONCILING；有providerTaskId但尚未迁移状态的分支继续查询。增加“未到租约期限就重启”的集成测试，不能仅在测试中手工调用recover后宣称覆盖启动生命周期。

证据级别：源码控制流确认，未做真实进程kill集成复现。

### R2 [P1] 释放租约没有 fencing，可清除另一 worker 的有效租约

位置：creative/repository/MediaJobRepository.java:220；creative/job/MediaJobScheduler.java:144。

releaseLease仅WHERE id；旧worker租约失效后，若新worker已经认领，旧worker finally仍会清除新worker租约。调度器还会一次认领整批再串行调用网络，没有续租，慢请求使尾部任务更容易在实际处理前过期；后续重新读当前记录而非绑定最初claim token会扩大并发竞争。

建议：认领返回不可变(owner,epoch)令牌；认领、续约、处理前校验、释放均使用同一令牌，释放必须WHERE id+owner+epoch；按实际worker容量认领，或实现有界并行与续租。增加旧worker迟到释放不影响新worker、整批处理超过租约的测试。

证据级别：源码确认；本轮未执行多实例数据库竞争测试。不宣称已经观察到真实重复计费。

### R3 [P2] 本地存储未兑现符号链接越界保护

位置：creative/storage/LocalMediaStorage.java:28。

resolve只做normalize和startsWith，不验证真实路径或符号链接。临时目录构造base/linked→outside，outside/probe.txt写入测试哨兵，编译并调用仓库真实LocalMediaStorage.open("linked/probe.txt")，成功读出REVIEW_SENTINEL。只操作临时文件，无用户数据读取。

对象键由服务端生成，所以不能把该复现表述为任意远程用户可直接读任意路径；触发需本地媒体目录内存在对应符号链接。但A15明确要求拒绝此场景，现有测试只测../与归属，不能判完整PASS。

建议：校验真实根与路径各级链接、创建文件使用安全路径策略并考虑检查/使用竞态；增加符号链接读写/删除与失败临时文件清理测试。

证据级别：独立临时目录动态复现。

## 后续建议顺序

1. 修R1/R2/R3，补相应回归；复核C05 DONE和A12/A15完整PASS是否成立。
2. 补A04–A06、A22–A25、A31–A34等参数/隔离/竞态/智能体与工作流端到端证据；同步实际API与实施契约。
3. 完成真实生成A18、真实OSS与缩略图A17、桌面A37、备份A38及负载A40；真实收费测试依当前会话授权进行。
4. 整理验收表为逐项PASS/FAIL/BLOCKED/NOT_RUN并提供证据，C16通过后再扩展P2。

结论：P1主体实现已有明显进展，可作为待验收候选版继续推进；尚不满足原定P1发布门槛。没有修改业务实现或原验收状态。


## 后续处理（2026-10-30）

- R1 已修复：调度器每 30 秒执行一次周期恢复，快速重启后未过期的 SUBMITTING 任务也会被接管。
- R2 已修复：释放租约改为带 epoch 围栏，旧 worker 不会清除新 worker 的租约；新增用例 `leaseReleaseIsFencedByEpoch`。
- R3 已修复：本地存储以真实路径为边界，读写删除均拒绝符号链接逃逸；新增 `LocalMediaStorageTest`。
- 同时完成来源追踪、事件 reset、取消不支持提示、缩略图与本地素材视觉理解回退；全量测试 424 通过。
