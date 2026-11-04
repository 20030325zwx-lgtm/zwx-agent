# 工作 Agent 整改详细方案：执行可靠性、交互、目标与自动化

日期：2026-11-05  
状态：设计稿，未实施。新增表、字段、接口和组件均为拟议设计，不能据此认定功能已存在。  
源码基线：已包含 V33 子智能体调用归属及实时可视化；数据库依据仓库迁移文件核对，未读取本地连接配置或查询生产库。

## 1. 目标、范围与取舍

目标：用户给出目标后，能够看见进展、途中纠偏、确认具体操作、检查交付证据，并在断线或重启后有明确的恢复路径。对齐 Z Code 的工作流程体验，同时保留本项目知识库、文档生成、图片/视频创作的特点。

本方案覆盖上一轮整改清单，包括执行生命周期、同任务续答、调用链、验收、上下文、目标模式、输入队列、终端、浏览器、子智能体、引用、撤销、定时任务、记忆、任务管理、项目 Wiki。

本轮只交付方案，不修改业务代码、不执行 DDL、不重启服务、不自动提交。现有画布编辑等未提交改动保持原样。

设计约束：

1. 复用 Spring + PostgreSQL + 既有对象存储，首期不引入 Kafka/Redis 队列或第二套工作流引擎。
2. `agent_run` 继续表示一次被授权的工作；`agent_run_assignment` 表示执行器的一次尝试；新的 step 表才表示子任务。不要把 assignment 当作子智能体。
3. 先保证人工交互任务可靠，再开放无人值守。预算、权限、未知副作用都必须有停止边界。
4. 模型“说完成”不能直接使任务完成。交付文件、执行结果和验收证据必须能够核对。
5. 表按阶段迁移，不一次性创建所有扩展表。接口版本、迁移序号在实施时以仓库实际状态为准。
6. 规划不承诺完全复刻 Z Code，也不假设它内部采用本方案的数据库结构。

## 2. 已有基础与确认的差距

下列路径相对仓库根目录，Java 路径省略 `src/main/java/com/zwx/zwxagent/`。

| 范围 | 已确认实现 | 本次整改入口 |
| --- | --- | --- |
| 运行与恢复 | `agent/execution/AgentRunService.java`、`AgentRunRecoveryJob.java`；run 检查点、孤儿运行标记中断 | 抽统一启动入口，新增持久化派发、租约、恢复分类 |
| 阶段与执行尝试 | V27 的 phase/waiting 字段，V28 的 `agent_run_assignment` | 保留现有阶段名，细化等待/取消/预算原因，增加 fencing |
| 实时过程 | `agent/graph/RunStream.java`、`ActivityEvent.java`、`frontend/src/components/AgentTrace.vue` | SSE 内存历史转为持久化事件游标；统一主区/右侧数据 |
| 事件存储 | `agent_execution_event`、`AgentExecutionTraceService.java` 已存在 | 扩展事件结构和序号分配，不另建同用途 event 表 |
| 工具调用 | `AgentToolCallLedgerService.java`，V33 的 worker_key | 分离业务操作幂等与调用可视化身份，增加业务结果与证据关联 |
| 授权 | `ToolConfirmationService.java` 按 `(run_id, tool_name)` 记录，权限模式和用户规则已存在 | 增加操作级授权身份；持久化失败时不能仅在内存放行 |
| 等待用户 | `AskHumanTool.java`、`SuperAgent.vue` 的 submitHumanAnswer | 当前答复后发新消息/新 run，改成原任务内消费答复 |
| 长任务 | 图编排有 5 分钟上限，worker 上限 8 步，质检有有限返工 | 将单次尝试预算与跨轮目标预算分开，不能只提高常量 |
| 上下文 | `agent/hook/MemoryCompressionHook.java` 自动压缩 | 压缩失败会丢旧块；需结构化任务摘要和可恢复检查点 |
| 长期记忆 | `chat/memory/MemoryService.java`、`MemorySettings.vue` | 管理查询按用户，但 factsFor 注入查询只有 tenant/agent，去重索引也不含用户/项目；应优先隔离 |
| 工作区 | `platform/workspace/RunWorkspaceService.java`、`CodeWorkspace.vue` | 已有 worktree/diff/撤销；补本轮改动归因与安全恢复事务 |
| 终端 | `ProjectTaskTool.java` 等待进程结束后返回 | 增加持续日志、执行身份、取消与后台任务管理 |
| 浏览器 | `services/browser-mcp` 公网网页截图 | 不能视作交互式浏览器；新增会话、操作、页面状态与验收 |
| 定时 | `2026-11-04-scheduled-tasks-module.md` 已有规划 | 尚无通用调度定义/实例，本方案明确依赖与落表 |
| 配置与评测 | 模型目录、技能、提示词版本、用量和 eval 服务已有 | 复用配置/评测，不新增第二套模型和提示词管理 |

需在实施启动时复核：已配置 MCP 可能提供仓库外的能力；源码存在不代表当前环境启用。插件、模型、浏览器、文档引擎必须以能力探测结果显示。

## 3. 产品界面方案

### 3.1 主工作台

- 左侧：项目/会话列表，支持运行中、需处理、已完成、失败筛选；显示未读与等待标记。置顶和归档放菜单，删除不是默认整理方式。
- 中间：工作状态、任务目标/计划、实时调用链、正文与交付物。默认保留一个执行摘要，不重复展示三套操作次数。
- 右侧：上下文详情面板。点文件显示文件/diff，点命令显示日志，点浏览器任务显示网页，点子任务显示任务详情。没有选择时默认收起或显示当前计划。
- 底部：输入框、引用条、模型/权限/技能；执行中仍可输入，发送时选择“下一轮处理”或“当前任务补充”。

布局建议：左栏 240–280px 可折叠，中间最小 480px；右栏 360–480px 可调节。低于 1100px 时右栏抽屉化，低于 720px 时侧栏和详情互斥展开。状态与时间保持同一行，窄屏参数允许独立换行。

### 3.2 执行过程

状态行示例：`工作中 01:25 · 2/4 个子任务 · 7 次操作`。等待时显示 `等待你确认 · 1 项`；后台仍有命令时显示 `后台运行 · 1 项`，不能直接写“已完成”。

默认展示当前阶段、正在执行的子任务和最近几项操作；完整链按需展开。筛选有“全部”和具体子任务，每个子任务显示名称、角色、当前状态、耗时、产物和失败原因。

单个操作行：工具动作、关键参数、执行状态、耗时。详情展示已脱敏参数、返回摘要、结构化结果、日志/文件证据链接。长日志按游标加载，不把全文塞进每条 SSE。

工具状态与业务结果分开：`工具已返回 / 测试失败(exit=1)`、`工具异常 / 网络超时`、`操作结果未知 / 待确认`。

调用链只显示可公开的操作和简明决策摘要，不以“思考过程”名义要求或展示模型隐藏推理。

### 3.3 人工交互

- 补充问题：题目、选项/输入框、关联子任务；提交后显示已接收、已应用，不生成另一套任务。
- 操作确认：实际命令/文件路径/变更摘要、作用域、原因；默认“仅允许此次”。“本会话/本项目允许”作为独立选择，不隐式扩大授权。
- 暂停：停止启动新步骤，正在运行的副作用操作先确认能否安全停；界面显示“正在暂停”，不能立刻谎报已停止。
- 取消：明确取消当前 run；目标模式一并暂停。不会自动撤销已产生文件或外部数据。
- 普通选择题可在后期提供用户主动开启的超时默认值；权限请求与计划批准不自动放行。

### 3.4 交付与验收

收尾卡片显示：已完成项、文件/素材、代码改动、测试结果、未完成项。每条验收项可打开证据。文件支持预览/下载/继续修改；媒体继续复用创作资产和候选选择能力。

“本轮改动”与“项目当前未提交改动”两个视图明确区分。运行结束不自动提交、推送或发布。

## 4. 执行架构与状态

### 4.1 分层

1. HTTP 命令入口：校验用户、项目和模型，接收启动/答复/暂停请求；不持有任务生命周期。
2. `AgentRunApplicationService`（拟新增）：统一组装模型、工具、上下文、工作区、权限与预算，供聊天/目标/定时调用。
3. 派发器：从数据库认领 QUEUED run，创建 assignment，获取租约与 fencing token，在事务外执行。
4. 运行协调器：维护 step 依赖、输入消费、检查点、证据与恢复。继续使用现有图编排，不重写图引擎。
5. 事件服务：持久化状态事件；SSE 是观察通道，断开不自动等价于取消。
6. 执行适配器：先 local，之后受控 command/browser；桌面/远程能力未落地前不展示可选入口。

### 4.2 三层身份

| 身份 | 生命周期 | 用途 |
| --- | --- | --- |
| run_id | 一次工作从接收到完成 | 用户授权、输入、输出、预算与所有权 |
| assignment_id + attempt | 一次执行器认领 | 故障恢复、租约、过期写入拦截 |
| step_id + plan_revision + step_key | 计划中的一个子任务 | 依赖、工具归属、重做、验收 |

同 run 续答不创建新 run；恢复可创建新 assignment；改变目标产生新 goal revision，后续迭代用新 run。plan_revision 与现有 `agent_run.revision`（返工计数）分开命名，不能混用。

### 4.3 状态机

保留 V27 `phase`：QUEUED、WORKING、WAITING_HUMAN、BLOCKED、FAILED、DONE、STOPPED。新增 `control_state`：NONE、PAUSE_REQUESTED、CANCEL_REQUESTED；原因写 `stop_reason`/`waiting_reason`。

| 触发 | 状态变化 | 操作 |
| --- | --- | --- |
| 接收任务 | → QUEUED | 同事务写 run、助手占位消息和 run.created |
| 派发成功 | QUEUED → WORKING | 认领租约，生成 assignment |
| 需要答复/授权 | WORKING → WAITING_HUMAN | 持久化请求与检查点，释放执行线程 |
| 收到有效答复 | WAITING_HUMAN → QUEUED | 原 run 入队，答复仅消费一次 |
| 用户暂停 | control_state=PAUSE_REQUESTED；安全点 → STOPPED | stop_reason=USER_PAUSED，可恢复 |
| 用户取消 | control_state=CANCEL_REQUESTED；停止确认后 → STOPPED | stop_reason=USER_CANCELLED，不自动恢复 |
| 操作结果不明 | → BLOCKED | stop_reason=UNKNOWN_EFFECT，先对账再决定 |
| 暂时缺资源 | → BLOCKED 或有界延迟重试 | 不能把永久权限拒绝当网络问题重试 |
| 全部必要步骤与验收通过 | → DONE | 原子写最终结果和完成事件 |
| 不可恢复错误 | → FAILED | 保留错误分类和已完成成果 |

旧 `status` 由唯一适配层投影：QUEUED/WORKING → RUNNING；WAITING_HUMAN/BLOCKED/STOPPED → INTERRUPTED；DONE → COMPLETED；FAILED → FAILED。所有写入迁到统一服务，禁止页面、扫尾任务各自修改一套含义。

### 4.4 派发与恢复

- 扫描用 `FOR UPDATE SKIP LOCKED`，事务只做认领/写入，绝不在数据库事务内等待模型或命令。
- 每次认领递增 fencing_token；心跳续约以及状态/证据写入都带 token，拒绝旧 assignment 的迟到更新。
- 会话并发槽、项目写锁、run 租约统一按实际执行状态续约；等待人工释放执行槽，后续恢复重新检查资源与权限。
- 数据库 fencing 不能阻止旧进程继续写本地文件：项目写锁到期前执行器必须主动停写；无法证明旧进程停止时 BLOCKED，不把项目直接交给新执行器。远程执行需额外资源侧 fencing。
- 恢复分类：排队未执行→重新认领；已完成步骤→复用；纯读取未完成→允许有界重试；写入/外部提交结果未知→查询外部任务或人工处理。
- 外部 API 不承诺 exactly-once。相同业务操作重投必须使用稳定 operation_key；供应商支持时传 idempotency key，否则记录外部 ID 并对账。
- 断开页面仅移除观察者。只有用户取消、预算到期、租约失效或明确策略能停止执行。

## 5. 功能实施细则

### 5.1 持久化实时事件

复用 `agent_execution_event`，事件类型包括 run/step/tool/input/command/artifact/verification 的创建、更新和终态。事件 schema_version 固定格式，状态快照与事件分开。

以 run 行上的 `event_seq` 原子递增分配序号，同事务写业务状态和事件。现有其他 agent 的事件写入同步改为按租户/会话/run 的事务锁分配，避免旧 MAX+1 与新生产者并发；没有 agent_run 行的旧 agent 不强行加 run 外键。

SSE ID 使用 `{runId}:{sequence}`，客户端按 sequence 去重。先获取快照及其 cursor，再读取 `sequence > cursor` 的事件，消除先订阅/后快照造成的漏事件。游标过期返回 410 + 最早可用序号，前端重新加载快照。

首期事件表兼作持久化通知源：SSE 网关按游标查询，内存通知仅作低延迟唤醒。不要实现“先写库、再发送一次，失败就永久丢失”的通知链。

`agent_tool_call` 更新与 tool.updated 事件同事务。模型输出按 100–250ms 合批持久化或通过独立正文版本游标恢复，不能每 token 写一行。命令日志单独分块，事件只带可读到的最大偏移。

### 5.2 子任务、调用身份与幂等

持久化 step 的输入、依赖、状态与结果；步骤必须等待全部依赖成功且产物可用才进入 READY。失败传播为 SKIPPED/BLOCKED，非必要步骤失败是否允许交付由验收条件决定。

V33 的 worker_key 只是当前的可视化归属，不能作为长期业务幂等边界。相同读取在两个子任务中可以各执行一次；同一付费提交跨子任务重试不能因 worker_key 改变而再收费。

引入两个概念：

- invocation_key：一次逻辑工具调用的身份，模型回复中的 tool-call id 与 step/checkpoint 绑定，重放不变；新的有意调用使用新身份。
- operation_key：有副作用业务动作的稳定去重身份，由可信服务在首次意图落库时分配并保存；不得让模型随便生成，也不能简单用参数相同永久去重。

先落调用意图再执行；每次重试的执行事件都可见，但不会伪造第二次业务操作。已成功调用被复用时显示“复用结果”；并发重复请求等待已存在的执行结果，不把正常在途 PENDING 自动认定为中断 UNKNOWN。只有确认执行租约失效且结果无法核实时才转未知。

### 5.3 同任务问答、授权与排队

`agent_run_input` 保存 QUESTION、ANSWER、STEER、NEXT_TURN、PLAN_APPROVAL 等输入，建立答复与原问题的关联。

答复事务锁定原 run 和问题，验证 ownership/version，写答案并标记请求已回答；run 入队。恢复时检查点记录 last_consumed_input_id，答案注入对应 step 的上下文；不会重写最初用户请求，也不会重复原回答。

STEER 在模型调用/工具完成后的安全点应用。若会使尚未执行计划失效，生成新 plan_revision，保留旧步骤和已发生动作；不能在工具写入过程中瞬间改变执行目标。NEXT_TURN 在前一 run 收尾后才创建下一 run，沿用已保存的输入与模型选择。

工具批准匹配 `run + operation_key + 参数指纹 + 工作区 + 权限修订`。模型恢复后换了命令/路径需重新批准；明确选择项目规则才写 `agent_permission_rule`，普通“允许一次”不写长期规则。权限失效、DB 写授权失败均不能仅凭内存缓存放行。

### 5.4 验收与产物

`agent_run_evidence` 记录 FILE、COMMAND_EXIT、TEST_REPORT、BROWSER_ASSERTION、MEDIA_OUTPUT 等证据。每条证据绑定 step/call、产生时间、文件哈希或对象版本，标明 producer，不能把用户上传附件误当 Agent 新产物。

确定性验收先行：文件存在/可打开、JSON schema、退出码、必要测试结果、页面断言。再用模型检查内容覆盖度；模型评价必须引用 evidence_id。未验证与验证失败分别显示，明确验收条件被跳过时不能自动 PASS。

`verification_status` 使用 PASS/FAIL/NOT_RUN/INCONCLUSIVE，对接旧值通过适配器。测试失败的命令可保留 transport=SUCCEEDED，但 business_outcome=FAILED。

### 5.5 上下文与记忆

短期上下文：run/step/checkpoint 内保存任务契约、完成清单、未完成清单、关键决策、引用索引。压缩保留用户硬约束、授权和工具调用配对；失败时保留原检查点并暂停/重试摘要，不直接删除关键历史。

预算由模型能力目录给出，先计入系统提示词、工具 schema、历史、附件和预计输出。摘要不能固定认为所有模型都可承载相同字符数。界面显示上下文使用估计值、压缩发生时间和引用来源。

长期记忆：第一批先修 tenant+user 读取/去重隔离；项目记忆在后续加入 project_id/scope。既有 user_id 空值或来源不明记录进入待治理，不默认向所有用户注入。项目事实、个人偏好和团队规则分别授权；普通用户记忆不能升级为系统/项目强制指令。

项目指令读取与自动记忆分开：主任务和子智能体记录实际注入的 AGENTS.md 路径/哈希；可显示来源，不把目录中的任意文档当成指令。MCP/网页/附件内容作为数据使用。

### 5.6 终端与浏览器

终端首期只包装现有允许的项目任务，暂不开放任意系统命令。启动返回 commandSessionId；日志独立持续读取；退出、取消、超时分别记录。日志带 chunk_seq/offset，慢客户端可回补；量大时转对象存储。

进程身份必须包含执行器实例、process_ref、启动时刻，不能重启后仅凭复用的 PID 杀进程。取消整个进程组；执行器失联后若无法确认进程结束，结果为 UNKNOWN。前台转后台不改变授权及预算。

浏览器后续新增持久会话和标签归属；按 snapshot→定位→操作→再观察的循环执行，保存动作与截图证据。网页内容不能创建新的执行授权。只操作授权标签，外部发送/提交按操作权限确认。

本地 localhost 页面由本地/桌面执行器访问，不直接取消服务器截图服务的内网防护；服务端和桌面端浏览器能力分别探测。浏览器存活句柄是临时资源，重启后不能把数据库 session 行当作仍然登录的活浏览器。

### 5.7 改动审阅与撤销

受控编辑前保存原始内容、文件是否存在、权限模式、哈希，编辑后保存新版本引用。大文件放对象存储，表只存 key/哈希。rename 记录 old/new path，delete 也保存原文；路径使用工作区相对路径并校验符号链接。

撤销先预检所有文件：当前哈希必须匹配本轮 after_hash，目标路径与工作区身份不变；拿项目写锁后再次检查。失败不部分写入。执行时采用临时文件、逐文件恢复日志和补偿；多文件文件系统操作不声称具备数据库原子性，进程崩溃时必须 BLOCKED 并按恢复日志补偿。

终端命令产生的任意文件变动不能假装都可安全撤销。首期只显示 diff 并标记“不支持本轮自动恢复”；有独立 worktree/完整快照时再开放更大范围恢复。

### 5.8 目标模式

一个会话最多一个活动目标，包含目标文本、可校验条件、预算和版本。一次迭代关联一个 run；goal_revision 和目标版本保持一致。

迭代结束：读取真实证据→判断完成/继续/阻塞→持久化结论及下一轮输入→若仍 ACTIVE 且预算足够，原子创建下一轮。唯一 `(goal_id, iteration_no)` 防止重复续跑。

预算同时覆盖 planner/worker/verifier/摘要/目标校验的消耗；并行调用先预留预算，结束后结算。token 是供应商结果时的硬约束能力有限，应限制 max_output、并发和调用数，并如实标记估算。停止条件：成功、用户暂停、预算耗尽、连续无进展、未知副作用。

目标替换递增 revision：旧 run 的迟到结果可归档，但不能开启旧目标的下一轮。只读计划模式不得隐式转为自动执行。

### 5.9 定时任务

复用 [已有定时任务规划](2026-11-04-scheduled-tasks-module.md) 的页面和时间语义，本方案补充统一 run/assignment 接入。

首期：一次性、每日、工作日、每周；时区、未来 5 次预览、暂停恢复、立即运行、结果历史、错过记录。编辑生成 definition revision，已入队实例冻结旧版本。

扫描事务锁任务行，写唯一计划实例并推进 next_run_at；dispatch 使用实例 ID 作为启动幂等来源。默认同任务不重叠、错过跳过。暂停只阻止新的自动触发，不默认取消当前任务；手动立即运行不修改自动 next_run_at。

首期实例只启动 AGENT；原计划中的 scheduled_task_attempt 暂不建，执行尝试直接复用 `agent_run_assignment`。后期接入画布/工作流时，若跨引擎调度需要独立派发尝试，再增表，不能把媒体轮询复制到通用调度器。

等待答复/授权的实例为 WAITING_HUMAN 并产生待处理通知，不算成功也不自动提升权限。电脑休眠/服务停机不能按点执行，恢复时记录 missed/skip_reason。

### 5.10 自定义角色、引用和扩展

自定义子智能体由 profile + 不可变 version 管理。运行只引用已启用且有权使用的版本，冻结模型、工具、提示词和 maxTurns；角色授权始终取角色配置与主 run 权限交集。

引用文件/目录/历史会话/技能先解析为可审阅引用条；发送时冻结内容版本与预算。跨会话引用校验原会话权限，避免把看得见标题误当作有权读取全部消息。大附件走对象存储、按需读取。

任务通知仅包含完成、失败、等待人工等有意义变化；浏览器/系统通知只是站内通知的投递方式，不默认接入邮件和消息平台。

Wiki 以 commit 和目录指纹标记版本，生成途中逐页可读；源代码变化先标过期，有预算且允许自动刷新时才更新，不每轮对话无条件消耗模型。

## 6. 数据库设计约定

以下是字段级设计清单，不是可直接执行的迁移脚本。`NN` 表示 NOT NULL；`?` 表示可空；未单列时间字段的新表默认具有 `created_at TIMESTAMPTZ NN DEFAULT now()`，可变表另有 `updated_at`。所有状态用 VARCHAR + CHECK，变更状态有 CAS/version 校验。

- 现有主键类型保持：run/assignment 为 VARCHAR(36)，conversation/project/user/tenant 为 VARCHAR(64)，工具/消息 ID 为 BIGINT。新业务主键默认 UUID；引用旧表必须用原类型，不能混写 UUID 外键。
- 所有新业务表带 `tenant_id VARCHAR(64) NN`、`user_id VARCHAR(64) NN`。用户和租户由服务端身份填充，客户端不能指定。
- 关联现有 run 等表时先增所需的唯一 `(id, tenant_id, user_id)` 候选键，使用复合 FK 防跨租户关联；现有表所有权必须可回填后再收紧约束。每个 API 同时做 actor 作用域检查。
- 索引优先 ownership+业务筛选+时间游标；查询明确用到 JSON 条件时才建 GIN。分页使用 keyset，避免长会话一次读完。
- 新 JSON 都记录 schema_version，禁止塞密钥、Cookie、原始认证头。内容正文/日志的访问与列表元数据分权，服务端脱敏，不只依赖 Vue 正则。
- 活动任务不因普通归档被删除。硬删除先终止运行、检查关联目标/计划，再按保存期清理；对象存储删除用延迟 GC，避免 DB 回滚后文件已消失。

## 7. 现有表修改清单

### 7.1 agent_run — 执行、控制与预算（A/B/C 阶段渐进加入）

| 新增字段 | 类型/默认 | 用途 |
| --- | --- | --- |
| engine_version | SMALLINT NN DEFAULT 1 | 1 走旧恢复逻辑，2 走新底座 |
| execution_mode | VARCHAR(16) NN DEFAULT 'INTERACTIVE' | INTERACTIVE/BACKGROUND |
| trigger_source | VARCHAR(16) NN DEFAULT 'CHAT' | CHAT/GOAL/SCHEDULE |
| start_request_key | VARCHAR(128) ? | 外部启动幂等；唯一 `(tenant_id,user_id,start_request_key)`，仅非空 |
| state_version | BIGINT NN DEFAULT 0 | 乐观锁，与旧 revision 分开 |
| plan_revision | INTEGER NN DEFAULT 0 | 计划版本 |
| event_seq | BIGINT NN DEFAULT 0 | 事件序号分配，存量按 MAX(sequence) 回填 |
| control_state | VARCHAR(24) NN DEFAULT 'NONE' | 暂停/取消请求 |
| stop_reason | VARCHAR(48) ? | 明确暂停、取消、未知结果、预算等原因 |
| recovery_policy | VARCHAR(24) NN DEFAULT 'MANUAL' | MANUAL/SAFE_AUTO；历史默认不自动重放 |
| next_dispatch_at | TIMESTAMPTZ ? | 排队与延迟重试时间 |
| lease_until | TIMESTAMPTZ ? | 执行租约，owner_instance 复用已有字段 |
| fencing_token | BIGINT NN DEFAULT 0 | 拒绝旧执行器写入 |
| last_heartbeat_at | TIMESTAMPTZ ? | 执行心跳 |
| budget_config | JSONB NN DEFAULT '{}' | 时长、调用数、token 上限，含 schema_version |
| deadline_at | TIMESTAMPTZ ? | 本次 run 截止时间，不覆盖原后台媒体 deadline |
| context_version | INTEGER NN DEFAULT 0 | 当前任务摘要/上下文版本 |
| last_consumed_input_id | UUID ? | 最近应用的用户输入，引用 run_input |
| goal_id / goal_revision | UUID ? / INTEGER ? | C 阶段目标归属 |
| scheduled_task_run_id | UUID ? | C 阶段调度归属，非空唯一 |

保留 phase/status/waiting/background/prompt_versions/model 等已有字段；prompt_tokens/completion_tokens/llm_calls 后期提升 BIGINT，避免长目标计量溢出。新增队列部分索引 `(next_dispatch_at,id) WHERE phase='QUEUED'` 和租约索引 `(lease_until) WHERE phase='WORKING'`。

### 7.2 agent_run_assignment — 尝试租约

新增 `owner_instance VARCHAR(36)?`、`fencing_token BIGINT NN DEFAULT 0`、`lease_until/heartbeat_at TIMESTAMPTZ?`、`end_reason VARCHAR(48)?`、`error_code VARCHAR(64)?`。沿用 status=PENDING/RUNNING/ENDED，具体结果由 end_reason 表示，不另造第二个 run 状态。

保留 UNIQUE(run_id,attempt)，补租约过期索引；同 run 仅一个有效执行租约，由 run 行 CAS 约束，不能单靠状态字符串。

### 7.3 agent_execution_event — 统一持久化事件

将 sequence 从 INTEGER 改 BIGINT；新增 `user_id VARCHAR(64)?`（回填后按可识别数据收紧）、`event_type VARCHAR(64)?`、`schema_version SMALLINT NN DEFAULT 1`、`step_id UUID?`、`tool_call_id BIGINT?`、`assignment_id VARCHAR(36)?`、`dedupe_key VARCHAR(160)?`。

保留 phase/summary/detail/created_at，detail 保存脱敏 payload。新增 UNIQUE(run_id,dedupe_key) WHERE dedupe_key IS NOT NULL、`(run_id,step_id,sequence)`；已有 UNIQUE(run_id,sequence) 继续使用。

旧事件没有可靠时间/归属的，不倒填虚构值。该表也服务非 Manus agent，不能对全部 run_id 强加 agent_run FK；新 super 事件的关联由受控写入口保证，并验证各引用属于同 run。

### 7.4 agent_tool_call — 调用与业务操作

新增 `step_id UUID?`、`invocation_key VARCHAR(160)?`、`operation_key VARCHAR(160)?`、`replay_of_call_id BIGINT?`、`business_outcome VARCHAR(24)?`、`error_code VARCHAR(64)?`、`duration_ms BIGINT?`、`result_object_key TEXT?`、`display_arguments JSONB?`、`schema_version SMALLINT NN DEFAULT 1`。

保留 status=PENDING/SUCCEEDED/FAILED/UNKNOWN，business_outcome=SUCCEEDED/FAILED/PARTIAL/UNKNOWN。未知/旧纯文本返回不能靠关键词强行归类。

迁移唯一键：先对旧记录填 `invocation_key='legacy:'||id`，再建立 UNIQUE(run_id,invocation_key)；移除 V33 的 `(run_id,worker_key,tool_name,arguments_hash)` 唯一约束。对非空 operation_key 建 `(run_id,operation_key)` 部分唯一索引；复用尝试写新的审计调用行时 operation_key 为空，replay_of 指向原行。新增新记录必须通过服务保证两类 key 的语义。

此迁移涉及幂等行为，必须在新 dispatcher/wrapper 就绪且旧执行器退出后切换。engine_version=1 的旧 run 保留旧适配读取，不默认将历史调用重做。

### 7.5 agent_tool_confirmation / agent_permission_rule — 授权粒度

confirmation 新增 `tenant_id/user_id VARCHAR(64)?`、`operation_key VARCHAR(160)?`、`arguments_hash CHAR(64)?`、`workspace_ref VARCHAR(128)?`、`scope VARCHAR(16) NN DEFAULT 'ONCE'`、`policy_revision BIGINT?`、`expires_at TIMESTAMPTZ?`、`consumed_at TIMESTAMPTZ?`、`decided_by VARCHAR(64)?`、`request_version INTEGER NN DEFAULT 1`。

operation_key 不依赖真正工具执行已开始：审批前先生成操作意图。新索引 UNIQUE(run_id,operation_key) WHERE operation_key IS NOT NULL；迁移后废除 run+tool_name 的宽泛批准判断。旧批准标为 LEGACY_RUN_TOOL，仅用于旧引擎续跑且不得自动迁成项目永久授权。

permission_rule 新增 `scope VARCHAR(16) NN DEFAULT 'USER'`、`scope_key VARCHAR(64) NN DEFAULT ''`（PROJECT 时项目 ID）、`constraints_json JSONB NN DEFAULT '{}'`、`revision BIGINT NN DEFAULT 1`。替换唯一键为 `(tenant_id,user_id,scope,scope_key,tool_pattern,effect)`；constraints 可限制目录/命令前缀，匹配失败进入确认而不是放行。

### 7.6 agent_memory_fact — 先隔离，后治理

A 阶段：修复查询必须传 user_id；替换旧唯一索引为 `(tenant_id,user_id,agent_key,fact_hash)` 的非空用户部分索引。user_id 空值记录禁止注入，来源会话可明确归属时再回填。不要为无法确认的历史记录随意指定所有者。

D 阶段再新增：`scope VARCHAR(16) NN DEFAULT 'USER'`、`project_id VARCHAR(64)?`、`status VARCHAR(16) NN DEFAULT 'ACTIVE'`、`revision INTEGER NN DEFAULT 1`、`source_message_id BIGINT?`、`expires_at TIMESTAMPTZ?`、`updated_at TIMESTAMPTZ NN DEFAULT now()`。

USER scope 用无 project 的部分唯一索引；PROJECT scope 用 `(tenant_id,user_id,agent_key,project_id,fact_hash)`。团队共享记忆暂不开放，防止用户级表被默认共享。允许 ACTIVE/SUPERSEDED/DELETED/REVIEW_REQUIRED；查询注入只取有效 ACTIVE。

### 7.7 agent_conversation / agent_chat_message

conversation 在 D 阶段新增 `pinned_at/archived_at TIMESTAMPTZ?`、`group_name VARCHAR(80)?`、`sort_order BIGINT NN DEFAULT 0`、`last_read_message_id BIGINT?`、`state_version BIGINT NN DEFAULT 0`。首期简单分组名即可，不为颜色/排序单独新增分组表。

chat_message 在 B 阶段新增 `revision INTEGER NN DEFAULT 1`、`supersedes_message_id BIGINT?`、`context_snapshot JSONB NN DEFAULT '{}'`；新修改保留原记录并关联，不直接覆盖运行历史。现有 client_request_id 全局唯一逐步替换为 `(conversation_id,client_request_id)` 部分唯一，写接口始终按 actor 查 conversation。

## 8. 新增表清单与字段

### 8.1 A 阶段：可靠执行底座（4 张）

#### agent_run_step：子任务和依赖

- `id UUID PK`；ownership 公共字段；`run_id VARCHAR(36) NN`；`plan_revision INTEGER NN`；`step_key VARCHAR(128) NN`；`parent_step_id UUID?`。
- `title VARCHAR(255) NN`、`role_key VARCHAR(64) NN`、`definition_version_id UUID?`（C 阶段加 FK）、`input_snapshot JSONB NN`、`depends_on JSONB NN DEFAULT '[]'`、`acceptance JSONB NN DEFAULT '[]'`。
- `status VARCHAR(24) NN`：PENDING/READY/RUNNING/WAITING_HUMAN/SUCCEEDED/FAILED/SKIPPED/BLOCKED/CANCELLED。
- `attempt INTEGER NN DEFAULT 0`、`state_version BIGINT NN DEFAULT 0`、`assignment_id VARCHAR(36)?`、`result_summary TEXT?`、`checkpoint_id UUID?`、`started_at/finished_at TIMESTAMPTZ?`。
- UNIQUE(run_id,plan_revision,step_key)；索引(run_id,status)。depends_on 只允许引用同 run 同 plan_revision 的 step_key；写入时校验 DAG 与不存在依赖。

#### agent_run_input：问题、答案、纠偏和队列

- `id UUID PK`；ownership；`conversation_id VARCHAR(64) NN`、`run_id VARCHAR(36)?`、`step_id UUID?`、`parent_input_id UUID?`、`input_seq BIGINT GENERATED ALWAYS AS IDENTITY`。
- `kind VARCHAR(24) NN`、`payload JSONB NN`、`client_request_key VARCHAR(128) NN`、`status VARCHAR(24) NN DEFAULT 'PENDING'`、`version INTEGER NN DEFAULT 1`。
- `created_by VARCHAR(64)?`、`answer_deadline TIMESTAMPTZ?`、`accepted_at/applied_at TIMESTAMPTZ?`、`result_run_id VARCHAR(36)?`。
- UNIQUE(tenant_id,user_id,client_request_key)；索引(conversation_id,status,input_seq)、(run_id,status)。ANSWER 关联 QUESTION；重复答复同一请求只可生效一次；NEXT_TURN 被消费后关联 result_run_id。
- 状态：PENDING/ACCEPTED/APPLIED/REJECTED/CANCELLED/SUPERSEDED。修改队列用版本锁，不允许覆盖已被执行器认领的输入。

#### agent_run_checkpoint：可恢复上下文

- `id UUID PK`；ownership；`run_id VARCHAR(36) NN`、`step_id UUID?`、`checkpoint_no BIGINT NN`、`schema_version SMALLINT NN`、`plan_revision INTEGER NN`。
- `state_json JSONB NN`、`summary_json JSONB NN`、`message_object_key TEXT?`、`content_hash CHAR(64) NN`、`last_event_seq BIGINT NN`、`last_consumed_input_id UUID?`、`reason VARCHAR(32) NN`。
- UNIQUE(run_id,checkpoint_no)；索引(run_id,created_at DESC)。reason 为 WAITING/BEFORE_EFFECT/AFTER_STEP/COMPRESSION/PAUSE；摘要含任务契约，不序列化 ToolCallback、线程、数据库连接等运行时对象。
- 原 agent_run 的图检查点保留为旧引擎兼容缓存；新 checkpoint 是恢复输入的权威版本，不能两边独立编辑。

#### agent_run_evidence：可核对的验收证据

- `id UUID PK`；ownership；`run_id VARCHAR(36) NN`、`step_id UUID?`、`tool_call_id BIGINT?`、`kind VARCHAR(32) NN`、`producer VARCHAR(32) NN`。
- `artifact_ref TEXT?`、`content_hash CHAR(64)?`、`media_type VARCHAR(128)?`、`size_bytes BIGINT?`、`result_json JSONB NN DEFAULT '{}'`、`verification_status VARCHAR(24) NN DEFAULT 'NOT_RUN'`、`verified_at TIMESTAMPTZ?`、`criterion_key VARCHAR(128)?`。
- 索引(run_id,step_id,kind)、(run_id,verification_status)。result_json 保存 exitCode、assertions、测试摘要，不伪造进度。产物引用返回短时授权地址，列表不返回秘密 URL。

### 8.2 B 阶段：工作交互（4 张）

#### agent_command_session

`id UUID PK`；ownership；`run_id VARCHAR(36) NN`、`step_id UUID?`、`tool_call_id BIGINT?`；`executor_id VARCHAR(128) NN`、`process_ref VARCHAR(160)?`、`process_started_at TIMESTAMPTZ?`；`argv_json JSONB NN`、`cwd_ref TEXT NN`、`environment_profile VARCHAR(64)?`；`status VARCHAR(24) NN`（QUEUED/RUNNING/EXITED/FAILED/CANCELLED/UNKNOWN）；`exit_code INTEGER?`、`log_object_key TEXT?`、`last_log_offset BIGINT NN DEFAULT 0`、`deadline_at/started_at/finished_at TIMESTAMPTZ?`、`background BOOLEAN NN DEFAULT false`。

索引(run_id,status)、(executor_id,status)。不在环境字段保存密钥；日志按持久 chunk 序号/offset 读取，短日志可写磁盘但需明确宿主与保留期。

#### agent_artifact_change

`id UUID PK`；ownership；`run_id VARCHAR(36) NN`、`step_id UUID?`、`tool_call_id BIGINT?`；`workspace_ref VARCHAR(128) NN`、`change_seq BIGINT NN`、`path TEXT NN`、`old_path TEXT?`、`change_type VARCHAR(16) NN`（CREATE/UPDATE/DELETE/RENAME）；`before_object_key/after_object_key TEXT?`、`before_hash/after_hash CHAR(64)?`、`before_metadata/after_metadata JSONB NN DEFAULT '{}'`、`reversible BOOLEAN NN DEFAULT false`、`non_reversible_reason TEXT?`。

UNIQUE(run_id,change_seq)，索引(run_id,path)。同文件多次改动按序合并成展示，但原始快照保留；路径变化需记录两个目标。

#### agent_artifact_restore

`id UUID PK`；ownership；`run_id VARCHAR(36) NN`、`request_key VARCHAR(128) NN`、`direction VARCHAR(16) NN`（UNDO/REDO）；`status VARCHAR(24) NN`（PREPARED/APPLYING/SUCCEEDED/COMPENSATING/BLOCKED/FAILED）；`manifest JSONB NN`、`progress_json JSONB NN DEFAULT '{}'`、`workspace_ref VARCHAR(128) NN`、`error_detail TEXT?`、`finished_at TIMESTAMPTZ?`。

UNIQUE(tenant_id,user_id,request_key)，索引(run_id,created_at DESC)。manifest 保存 change_ids、预期当前哈希和目标快照，支持崩溃补偿；用户确认后预检仍需重做。

#### agent_context_reference

`id UUID PK`；ownership；`conversation_id VARCHAR(64) NN`、`message_id BIGINT?`、`input_id UUID?`；`kind VARCHAR(24) NN`（FILE/DIRECTORY/CONVERSATION/SKILL/ATTACHMENT）；`source_ref TEXT NN`、`source_version VARCHAR(160)?`、`content_hash CHAR(64)?`、`display_name VARCHAR(255) NN`、`snapshot_object_key TEXT?`、`selection_json JSONB NN DEFAULT '{}'`、`token_estimate INTEGER?`、`status VARCHAR(16) NN DEFAULT 'READY'`。

索引(conversation_id,message_id)、(input_id)。草稿引用未发送可回收；已发送引用冻结内容，不随源文件变化悄悄更新上下文。

### 8.3 C 阶段：自主执行（6 张）

#### agent_goal

`id UUID PK`；ownership；`conversation_id VARCHAR(64) NN`、`project_id VARCHAR(64)?`；`objective TEXT NN`、`acceptance JSONB NN`、`status VARCHAR(24) NN`（ACTIVE/PAUSED/COMPLETED/BLOCKED/BUDGET_EXHAUSTED/CANCELLED）；`revision INTEGER NN DEFAULT 1`、`budget_config/usage_json JSONB NN DEFAULT '{}'`、`next_iteration_no INTEGER NN DEFAULT 1`、`next_action TEXT?`、`stop_reason VARCHAR(48)?`、`completed_at TIMESTAMPTZ?`。

对 status IN(ACTIVE,PAUSED,BLOCKED,BUDGET_EXHAUSTED) 建 UNIQUE(conversation_id) 部分索引，保证一个尚未关闭的目标；替换必须显式修改版本或关闭旧目标。

#### agent_goal_iteration

`id UUID PK`；ownership；`goal_id UUID NN`、`goal_revision INTEGER NN`、`iteration_no INTEGER NN`、`run_id VARCHAR(36) NN`；`objective_snapshot TEXT NN`、`acceptance_snapshot JSONB NN`、`status VARCHAR(24) NN`、`verdict_json JSONB?`、`evidence_ids JSONB NN DEFAULT '[]'`、`next_input TEXT?`、`usage_json JSONB NN DEFAULT '{}'`、`finished_at TIMESTAMPTZ?`。

UNIQUE(goal_id,iteration_no)、UNIQUE(run_id)。verdict 取 COMPLETE/CONTINUE/BLOCKED，附证据与未完成项；目标当前版本与本行不符禁止继续派生。

#### agent_profile

`id UUID PK`；ownership；`name VARCHAR(80) NN`、`description TEXT NN`、`enabled BOOLEAN NN DEFAULT true`、`built_in BOOLEAN NN DEFAULT false`、`active_version INTEGER NN DEFAULT 1`、`archived_at TIMESTAMPTZ?`。

UNIQUE(tenant_id,user_id,name)，名称保留内置角色标识。删除采用归档，历史执行仍能读定义。

#### agent_profile_version

`id UUID PK`；ownership；`profile_id UUID NN`、`version INTEGER NN`、`system_prompt TEXT NN`、`model_row_id BIGINT?`、`reasoning_config JSONB NN DEFAULT '{}'`、`allowed_tools JSONB NN`、`required_mcp JSONB NN DEFAULT '[]'`、`max_turns INTEGER NN`、`budget_config JSONB NN DEFAULT '{}'`、`inject_project_rules BOOLEAN NN DEFAULT true`、`content_hash CHAR(64) NN`。

UNIQUE(profile_id,version)。版本不可变；模型引用复用 model_provider_model.id 并按可用权限校验，不复制 API key。

#### scheduled_task

`id UUID PK`；ownership；`name VARCHAR(160) NN`、`status VARCHAR(16) NN`（ACTIVE/PAUSED/COMPLETED/ARCHIVED）；`revision INTEGER NN DEFAULT 1`；`target_type VARCHAR(24) NN DEFAULT 'AGENT'`、`target_config JSONB NN`；`timezone VARCHAR(64) NN`、`schedule_type VARCHAR(24) NN`、`schedule_config JSONB NN`；`starts_at/ends_at/next_run_at TIMESTAMPTZ?`；`overlap_policy VARCHAR(24) NN DEFAULT 'SKIP'`、`misfire_policy VARCHAR(24) NN DEFAULT 'SKIP'`、`retry_policy JSONB NN DEFAULT '{}'`；`active_run_id UUID?`、`consecutive_failures INTEGER NN DEFAULT 0`、`automatic_run_count BIGINT NN DEFAULT 0`。

索引(next_run_at,id) WHERE status='ACTIVE'。active_run_id 指向 scheduled_task_run，仅为原子互斥槽，扫描/取消/终态归并都锁相同 task 行并清理；租户所有权和目标版本在保存与派发两次校验。

#### scheduled_task_run

`id UUID PK`；ownership；`task_id UUID NN`、`definition_revision INTEGER NN`；`scheduled_for TIMESTAMPTZ?`、`trigger_type VARCHAR(16) NN`（AUTO/MANUAL）、`trigger_key VARCHAR(160) NN`；`config_snapshot JSONB NN`、`status VARCHAR(24) NN`（QUEUED/RUNNING/WAITING_HUMAN/SUCCEEDED/FAILED/SKIPPED/CANCELLED/BLOCKED）；`agent_run_id VARCHAR(36)?`、`conversation_id VARCHAR(64)?`；`attempt_count INTEGER NN DEFAULT 0`、`next_attempt_at TIMESTAMPTZ?`、`skip_reason/error_code VARCHAR(64)?`、`result_summary TEXT?`、`started_at/finished_at TIMESTAMPTZ?`。

UNIQUE(task_id,trigger_key)、UNIQUE(agent_run_id) WHERE 非空；索引(task_id,created_at DESC)、(next_attempt_at) WHERE status='QUEUED'。AUTO trigger_key 基于任务+规则版本+计划时刻；MANUAL 使用请求 ID。task/run 双向可空 FK 分两步建立，避免插入循环。

### 8.4 D 阶段：扩展体验（6 张，按功能单独启用）

| 表 | 字段（除公共 ownership/时间） | 约束与用途 |
| --- | --- | --- |
| agent_browser_session | id UUID PK；run_id VARCHAR(36)；step_id UUID?；executor_id VARCHAR(128)；profile_ref VARCHAR(128)；status VARCHAR(24)；tabs_json JSONB；last_snapshot_ref TEXT?；expires_at TIMESTAMPTZ? | 索引(run_id,status)；cookie 不入此表；tab 归属由执行器校验；动作日志复用事件/证据 |
| agent_user_notification | id UUID PK；conversation_id VARCHAR(64)?；run_id VARCHAR(36)?；type VARCHAR(32)；title VARCHAR(160)；summary TEXT?；event_key VARCHAR(160)；read_at TIMESTAMPTZ?；delivery_state JSONB | UNIQUE(tenant_id,user_id,event_key)；索引(tenant_id,user_id,created_at DESC) WHERE read_at IS NULL |
| agent_saved_command | id UUID PK；name VARCHAR(80)；description TEXT?；prompt_template TEXT；input_schema JSONB；revision INTEGER；enabled BOOLEAN | UNIQUE(tenant_id,user_id,name)；只是可复用提示词入口，不能借命令模板绕过执行授权 |
| agent_repo_wiki | id UUID PK；project_id VARCHAR(64)；source_commit VARCHAR(64)?；source_fingerprint CHAR(64)；language VARCHAR(16)；status VARCHAR(24)；generator_run_id VARCHAR(36)?；version INTEGER；active BOOLEAN；stale_at TIMESTAMPTZ? | UNIQUE(project_id,version)；仅一个 active 版本，生成完成才切换；未提交目录以 fingerprint 标记 |
| agent_repo_wiki_page | id UUID PK；wiki_id UUID；page_key VARCHAR(128)；title VARCHAR(255)；sort_order INTEGER；content_object_key TEXT?；source_refs JSONB；status VARCHAR(24)；error_detail TEXT? | UNIQUE(wiki_id,page_key)，索引(wiki_id,sort_order)；单页失败不伪装整体完成 |
| agent_memory_revision | id UUID PK；fact_id BIGINT；revision INTEGER；fact TEXT；scope VARCHAR(16)；project_id VARCHAR(64)?；status VARCHAR(16)；source_refs JSONB；changed_by VARCHAR(64)?；change_reason TEXT? | UNIQUE(fact_id,revision)；审阅冲突和恢复旧内容，注入只读当前有效版本 |

D 表中未标 `?` 的业务字段默认 NN，JSON 集合默认 []/{}，版本默认 1，布尔默认见具体功能；状态集合需在对应功能 PR 中冻结并配 CHECK。Wiki/project、browser/run 等引用均应用本章统一的复合 ownership FK 规则。

汇总：A 新增 4 张，B 新增 4 张，C 新增 6 张，D 最多新增 6 张，共 20 张候选新表；按阶段引入。现有表修改共 9 类：run、assignment、event、tool_call、confirmation、permission_rule、memory_fact、conversation、chat_message。旧模型、技能、项目、媒体任务、工作流、提示词和 eval 表继续复用。

## 9. 接口方案

以下均为拟议接口。统一前缀 `/api/ai`；`R` 代表 `/manus/conversations/{conversationId}/runs/{runId}`。启动和恢复改为 POST 命令，GET 只读；旧 GET chat/resume 用兼容适配逐步淘汰。

| 阶段 | 接口 | 关键入参/返回 |
| --- | --- | --- |
| A | POST /manus/conversations/{id}/runs | message、contextRefs、modelRowId、permissionMode、clientRequestId；202 返回 runId/assistantMessageId/eventCursor |
| A | GET R/snapshot | phase/control、steps、必要调用摘要、待处理输入、证据、cursor；大结果分页 |
| A | GET R/events?afterSeq=&limit= | 持久化事件分页，nextCursor/hasMore |
| A | GET R/stream?afterSeq= | SSE 观察；不启动任务；支持 Last-Event-ID |
| A | POST R/pause、R/resume、R/cancel | expectedVersion、requestId；返回实际状态与控制请求状态 |
| A | POST R/inputs/{inputId}/answer | answer、expectedVersion、requestId；返回 ACCEPTED/APPLIED |
| A | POST R/confirmations/{confirmationId}/decision | decision、scope、expectedVersion、requestId；操作级批准 |
| A | GET R/evidence/{evidenceId} | 元数据和授权产物入口 |
| B | POST /manus/conversations/{id}/inputs | kind=STEER/NEXT_TURN、content、contextRefs、requestId |
| B | PATCH/DELETE /manus/conversations/{id}/inputs/{inputId} | expectedVersion；队列编辑/撤回 |
| B | POST /context-references/resolve | 工作区文件/会话/技能引用，返回可审阅元数据与预算估计 |
| B | GET R/commands/{id}/logs?offset=&limit= | chunks、nextOffset、completed；与命令状态读取分开 |
| B | POST R/commands/{id}/cancel | 请求停止进程组，返回控制状态 |
| B | GET R/changes | 本轮改动与可恢复性；项目 diff 旧接口保留 |
| B | POST R/restores/preview、R/restores | changeIds/direction；预检摘要，再 requestId+预期哈希执行 |
| C | POST/GET /manus/conversations/{id}/goal | 新建/读取目标、验收和预算 |
| C | POST /goals/{id}/pause、resume、replace、clear | 版本与幂等 ID；不混淆暂停和清除 |
| C | GET /goals/{id}/iterations | 迭代清单、证据和用量 |
| C | GET/POST /agent-profiles；POST /agent-profiles/{id}/versions | 可用角色列表、定义和不可变版本 |
| C | POST /agent-profiles/{id}/activate | 指定 version、expectedVersion；影响后续派工 |
| C | GET/POST/PATCH /scheduled-tasks[/{id}] | 时间/目标/权限、模型和版本配置 |
| C | POST /scheduled-tasks/preview | 无模型调用，返回未来 5 次时刻与时区摘要 |
| C | POST /scheduled-tasks/{id}/pause、resume、run、archive | 显式控制；MANUAL run 幂等 |
| C | GET /scheduled-tasks/{id}/runs；POST /scheduled-task-runs/{id}/cancel、retry | 历史/取消/重试，复用原实例，不制造新计划触发 |
| D | POST R/browser-sessions；GET R/browser-sessions/{id} | 已授权执行器与浏览器资源状态；控制动作须走工具授权 |
| D | GET /notifications；POST /notifications/{id}/read | 待处理与未读 |
| D | PATCH /manus/conversations/{id} | pin/archive/group/sort/expectedVersion |
| D | GET/POST /saved-commands；PATCH /saved-commands/{id} | 可复用命令模板 |
| D | POST /projects/{id}/wiki/runs；GET /projects/{id}/wiki | 生成、读取、版本与过期标记 |
| D | 扩展现有 memory API | scope/project/revision/history，不另建一套记忆接口 |

错误规范：400 参数/时间规则非法；404 不存在或无权；409 状态/版本冲突；410 事件已过保留期；422 模型/工具能力不支持；429 配额；503 执行器暂不可用。返回 requestId 和稳定 errorCode，不暴露凭据或内部堆栈。

## 10. 前后端代码改造分工

| 模块 | 现有入口 | 改造内容 |
| --- | --- | --- |
| 启动与恢复 | ManusAgentController、ManusGraphOrchestrator、AgentRunRecoveryJob | Controller 变薄；统一 application service；新旧引擎按 engine_version 分流 |
| 生命周期 | AgentRunService、RunPhaseSweeper、RunQuotaService | 原子状态更新、队列认领、租约、预算预留/释放；媒体等待沿用既有逻辑并统一终态判定 |
| 子任务 | WorkersNode、GraphState、GraphCheckpoint | 持久化 step 与 stable ID，等待人工安全退出；恢复不无条件重跑整组 worker |
| 工具链 | ToolGuardHook、IdempotentToolCallback、ToolCallWorkerContext | 明确 run/step/call/operation 身份；参数授权；业务结果；异步调用显式传播身份，避免仅靠 ThreadLocal |
| 事件 | AgentExecutionTraceService、RunStream | 先持久化后通知；兼容非 super agent；跨重启游标回放 |
| 记忆 | MemoryService、MemoryInjectionHook、MemoryCompressionHook | 所有权修复，任务契约摘要，失败不丢上下文 |
| 页面容器 | SuperAgent.vue | 抽出 useRunSession/useRunEvents/useRunInputs；去掉多套轮询与索引绑定 |
| 对话 | ChatRoom.vue、AgentTrace.vue、RunInspector.vue | 共用 run store，消息用稳定 message/run ID；详情按需加载，不按数组下标定位 |
| 输入 | TaskComposer.vue | 引用、消息队列、纠偏、待处理问题 |
| 改动/产物 | CodeWorkspace.vue、DeliverableCard.vue、ChangeSummaryCard.vue | 按轮快照、证据、预览与恢复确认 |
| 新页面/组件 | 拟新增 GoalPanel、RunInputCard、CommandPanel、ScheduleList/Editor/History、AgentProfileSettings | 分阶段建设，名称在实现时按现有规范定稿 |

## 11. 发布与数据库迁移计划

当前迁移最大编号 V33。下表使用批次代号，不占用真实 V34+ 文件名，避免与并行画布开发撞号。

| 批次 | 前置 | 内容 | 切换条件 |
| --- | --- | --- | --- |
| A0 | 无 | 记忆用户隔离、授权写失败闭锁；补运行身份 | 多用户隔离回归通过 |
| A1 | A0 | run/assignment/event 扩展；step/input/checkpoint/evidence 4 表 | 新生产者与旧读者兼容，快照/回放测试通过 |
| A2 | A1 | 新启动/恢复/问答/控制入口，授权及调用 key 切换 | 停旧执行器认领，存量 run 明确走旧恢复或人工迁移 |
| B1 | A2 | 命令会话、上下文引用、输入队列 | 运行中纠偏与日志重连通过 |
| B2 | B1 | change/restore 2 表、轮次审阅 | 撤销冲突/崩溃补偿通过 |
| C1 | A2+B1 | goal/iteration、profile/version | 预算/版本失效/重派测试通过 |
| C2 | A2 | scheduled_task/run，首期 AGENT | 固定时钟并发调度/暂停竞争/权限等待通过 |
| D | 按功能前置 | 浏览器、通知、Wiki、模板命令、记忆治理 | 独立启用，不作为 A/B 发布前置 |

迁移策略：expand→回填→双读校验→切换写入口→收紧约束→后续清理旧字段。大表回填分批，不在启动迁移里跑模型或文件扫描；唯一索引上线前检查存量重复。需要 CONCURRENTLY 的索引独立使用 Flyway 非事务迁移，不混入普通事务脚本。

DDL 上线前在脱敏数据库副本执行完整 V1→当前→新版本和存量升级测试。修改唯一键前枚举真实约束名，不假设所有环境都保留 PostgreSQL 自动生成名称。对任何重复/孤立数据生成治理清单，不静默删记录。

回滚：优先关闭新功能开关并停止新认领，保留新增表；已切换幂等唯一键后禁止直接回滚到仍使用旧 ON CONFLICT 的 JAR，必须先部署兼容旧读法的新适配版本或离线反迁移。等待中的任务保留待处理，不能为了回滚强制标成功。

本地配置 `application-local.yml` 不读写、不重建。本方案不变更现有终端开关，也不把浏览器访问本机等同于开放任意系统权限。

## 12. 测试与验收清单

| 分类 | 必测场景 | 判定 |
| --- | --- | --- |
| 多用户 | 同租户不同用户记忆/事件/引用/角色，跨项目资源 ID | 不串读、不串授权；未知所有者记录不注入 |
| 队列 | 两执行器同时认领、重试相同启动请求、租约过期旧执行器回写 | 只一个有效 owner；迟到写拒绝；不重复外部提交 |
| SSE | 接收快照时有新事件、乱序/重复、断线、跨重启、过期游标 | 最终状态一致；能恢复，不靠页面 index |
| 用户交互 | 同问题重复答复、过期答复、等待时重启、同时取消与答复 | 只消费一次；不创建意外新 run；冲突明确 |
| 权限 | 一次批准后换参数/路径、跨子任务复用、策略撤销、数据库故障 | 不扩大授权；无法持久化不放行 |
| 幂等 | 正常并发 PENDING、工具成功后进程崩溃、外部已提交但未记结果 | 区分在途与未知；优先对账，不盲重放 |
| 依赖 | 两上级一成功一失败、步骤被改版、重复 role 名 | 下游不提前执行；旧版结果不污染新计划 |
| 验收 | 文件名出现但文件不存在、exit=1、验证没跑、旧截图、供应商仍排队 | 不判成功；证据版本/来源可追溯 |
| 上下文 | 大工具响应、摘要超时、关键约束在早期、多轮目标 | 不丢契约/授权，不拆工具配对 |
| 命令 | 大量输出、慢观察者、进程树、超时、PID 复用、断线 | 可回补日志；取消对象准确；未知明确标记 |
| 撤销 | 用户后来编辑、文件删除/重命名、符号链接、恢复中崩溃 | 预检冲突不部分执行；恢复日志可补偿 |
| 目标 | 两个继续请求、暂停同时校验完成、替换目标、无进展、预算边界 | 无重复迭代；旧 revision 不再驱动新轮 |
| 定时 | DST、停机错过、编辑/暂停与扫描并发、上次等用户、手动连点 | 预览和执行一致；一实例；不挤压无限队列 |
| 浏览器 | 授权标签、登录失效、网页提示注入、真实提交确认、移动视口 | 操作基于观察，截图可复核，越权动作拒绝 |
| UI | 375/768/1440px、长中文/长命令、折叠后更新、后台未读、刷新运行中任务 | 状态可读、过程不消失、详情不遮输入 |

建议指标（目标值，尚未测量）：已写入事件到页面可见本地 P95≤1秒；恢复后首屏状态≤2秒；100 个并发重投请求只生成一个逻辑任务；相关用例全部通过后才提高并发。明确性能指标依赖测试硬件、数据规模与网络条件。

验证分层：纯函数与状态机单测→PostgreSQL 集成测试→假模型/假供应商故障注入→浏览器模拟 SSE/运行刷新→本地真实命令与测试页面→有预算的真实模型验收。构建通过不能替代运行中场景验证。

## 13. 可交付阶段与完成定义

| 阶段 | 用户可见结果 | 表变更 | 完成定义 |
| --- | --- | --- | --- |
| A | 任务不会因关页面误停；原任务答复继续；状态与证据可信 | 4 新表及执行/权限/记忆隔离扩展 | 页面断线/服务重启/重复答复/未知副作用/用户隔离均通过 |
| B | 执行中能补要求、看日志、引用文件、审阅并撤销本轮改动 | 4 新表及消息字段 | 长命令、消息队列、文件冲突与恢复通过 |
| C | 有预算地自动推进目标，可配置角色和定时任务 | 6 新表及 goal/schedule 关联 | 不反复要求“继续”；定时不重复；权限等待真实可恢复 |
| D | 浏览器验收、项目 Wiki、记忆治理、未读通知和任务整理 | 最多 6 新表及会话/记忆字段 | 每项单独完成真实功能回归与能力检测 |

推荐第一轮只执行 A，不把 20 张表一次建完。A0 的记忆隔离和授权边界属于基础正确性；A1/A2 的持久化运行模型是后续目标、定时和后台命令的共同前置。

估时不能仅依据表数量：A2 的图恢复和 B2 的安全撤销风险最高。实施前用一个“两个子任务→工具确认→答复→命令→产物验收”的纵向样例完成技术验证，再按团队人数拆工作日；本稿不虚构固定交付日期。

## 14. 参考来源与待确认边界

Z Code 官方资料（2026-11-05 对照；功能页面可能随版本变化）：

- [Agent 与上下文入口](https://zcode.z.ai/cn/docs/agents)
- [目标模式](https://zcode.z.ai/cn/docs/goal)
- [子智能体](https://zcode.z.ai/cn/docs/subagents)
- [安全操作确认](https://zcode.z.ai/cn/docs/safety-confirm)
- [浏览器自动化](https://zcode.z.ai/cn/docs/browser-use)
- [编辑历史与文件撤销](https://zcode.z.ai/cn/docs/edit-history)
- [定时任务](https://zcode.z.ai/cn/docs/automations)
- [记忆](https://zcode.z.ai/cn/docs/memory)
- [任务管理](https://zcode.z.ai/cn/docs/task-management)
- [项目 Wiki](https://zcode.z.ai/cn/docs/repo-wiki)

实施时需确认但不阻断当前设计：首发是本地桌面还是服务器多用户；真实模型验收预算；事件/日志/检查点保留期；本地浏览器登录方式。默认先支持现有 local 执行器、站内通知、用户私有角色和一次性/每日/工作日/每周定时。

长期记忆隔离问题属于源码确认的路径缺口；是否在实际环境发生跨用户注入取决于是否启用记忆、同租户用户和数据，本轮未测试或修改。修复实施时按仓库规范单独记录 bug 和回归结果。
