# DeepSeek dsh-superagent 研究原型评审：可借鉴点 vs Aster 现状

日期：2026-11-04
对象：https://github.com/deepseek-ai/dsh-superagent-prototype （MIT，TypeScript，2026-10-31 开源，约 1.7 万 star）
定位：**研究原型**，DeepSeek Harness 的一个 bundle/插件，不是官方产品，也不是通用多智能体框架。

## 1. 它是什么

一句话：**面向单个 GitHub 仓库的常驻主动型编码 Agent**。

监听 GitHub（可选飞书）→ 有人请求干活 → 在 Eval 层判断"该不该干、谁授权的、能干多大" →
开 Ticket（工单）→ 派给 Executor（独立 Session + 独立目录）→ 干活 → 通过 Channel 回到原对话交付（评论/PR）→
网页面板只显示"需要人处理的事"。

设计原则（000 篇）：*用尽量少的人类注意力换尽量大的价值* —— 只在被要求或团队写下的规则允许时动手，
只在"人必须行动或结果可用"时说话，一次只问一个具体问题，按影响面停手而不是按工作量停手。

## 2. 它的概念骨架

```
Source（事件流）──Trigger（不可变事件）──Thread（事件序列）
                     │                        ├─ Subscription 扇出 → 已存在的 Ticket（只给上下文，不给授权）
                     │                        └─ Eval（gate + 模型研判）→ Decision
                     │                                                   └─ accept → Ticket + Mandate
                     └── Channel（回写：评论/PR/飞书回复）                     ├─ Assignment → Executor(Session+目录)
                                                                              │        └─ Report(progress/waiting/ready/blocked/failed)
                                                                              └─ Attention（结构化"等人回答"）
```

几个关键设计：

- **授权与上下文分离**：Mandate 记录"凭什么能干、能干多大、依据哪版 Policy"；Subscription 只送上下文，不产生授权。
- **Ticket 与 Assignment 分离**：Ticket 是"一件被授权的工作"，Assignment 是"某个执行器执行的一次绑定"，可以多次、换执行器、崩溃重启，Ticket 状态不跟着执行器走。
- **Report 结构化**：执行器用结构化字段报告状态（含 `human_action` 提问），**不允许从自由文本里推断"是不是在等人"**。
- **Policy 版本化**：规则/接纳模式/各来源名单构成一个版本；Mandate 记住版本；版本变化会通知受影响的活 Ticket，但不直接吊销。
- **执行器不在领域内**：Executor 通过 Assignment 这个端口接入，换实现不影响领域模型。

## 3. 与 Aster 现状对照

| dsh-superagent | Aster 现状 | 差距 |
|---|---|---|
| Source（GitHub/飞书事件流） | 会话消息（用户发起）+ 活动抽屉；无外部事件源 | 没有"主动触发"入口 |
| Eval（gate 准入 + 模型研判） | `QwenIntentRouter` 意图识别 + 权限模式（PLAN/BUILD/EDIT/GUARDED/YOLO） | 有意图，但没有"准入/授权"判定；权限模式是全局策略而非逐任务 Mandate |
| Ticket（被授权的工作单元） | `agent_run`（RUNNING/COMPLETED/INTERRUPTED/FAILED） | run 状态少、没有"排队/等人/受阻"阶段；run 与执行器绑定太紧（中断只能续跑同一 run） |
| Assignment（派工） | 直接在 run 里跑图 | 没有"同一任务换执行器/重派"的概念 |
| Report（结构化状态） | run.status + verification + SSE 事件 | 等人确认靠 INTERRUPTED + canResume 推导；没有结构化 waiting 原因 |
| Channel（回写） | 会话消息 / 活动事件 | 没有对外部系统的回写通道（GitHub 等） |
| Policy + Mandate | 权限模式 + `ToolRiskCatalog` + 工具确认 | 无版本化、无"这次任务凭什么被批准"的记录 |
| 面板（只显示需要人的） | 任务列表 + RunInspector（计划/来源/文件/用量/工具/运行） | 信息更全但更重；没有"等人处理"聚合视图 |
| 后台 job/subagent 配额 | 无（run 结束即结束；媒体任务是另一套 lease/reconcile） | 长任务/后台会话模型缺失 |
| Outbox + Effects（事务性副作用队列） | `Idempotency-Key` + `agent_tool_call` 幂等账本 | 已有幂等，但没有"待发送/重试/顺序"队列抽象 |

## 4. 值得借鉴的（按性价比排序）

### 4.1 Ticket 语义化 run 状态（低成本、收益直接）
现状：`agent_run.status` 只有 4 个值，"等人确认""上游失败""排队"全靠前端推导（`INTERRUPTED + canResume`、
`awaitingPlan`、`pendingConfirmations`）。
建议：引入显式阶段 + 结构化等待原因：
`QUEUED / WORKING / WAITING_HUMAN(reason: CONFIRM_TOOL | CONFIRM_PLAN | NEED_INPUT | NEED_PERMISSION) / BLOCKED / FAILED / DONE / STOPPED`。
面板和任务列表按"第一匹配阶段"分组，标签写"等人做什么"而不是内部状态码。落点：`agent_run` 表 + `ManusAgentController` 状态映射 + 任务列表分组。

### 4.2 Ticket / Assignment 分离（中等成本，解决"重派"问题）
现状：run 中断后只能在同一 run 上续跑；换成不同执行器（比如本地 desktop runner）没有概念。
建议：把"这次执行"抽成 assignment（executor 类型、session id、目录、起止时间），run 只记结果与状态机；
崩溃恢复 = 新 assignment 接续，而不是改 run 内部 checkpoint。落点：`agent_run` 旁挂 `agent_run_assignment`。

### 4.3 结构化"等人"（低成本）
现状：确认弹窗只有工具确认；"还缺信息"只能靠模型自由文本说。
建议：工具层加 `humanAction(question, options?)` → run 进入 `WAITING_HUMAN(NEED_INPUT)` → 面板/对话流里是一条可回答的卡片，
而不是埋在一段长回答里。落点：新工具 + `confirmation-required` 类似事件扩展。

### 4.4 后台工作配额（中等成本，长任务体验）
现状：run 结束就结束；长命令/媒体任务靠另一套机制。
建议：run 结束时若仍有后台作业（媒体任务、长命令），状态保持 `WORKING` 并保留 slot；给"每轮后台工作上限"（如 30 分钟），
超时杀掉 shell 但不打断 subagent；handoff 给人时释放 slot。落点：`MediaJobService` 已有 lease/reconcile，可作为第一类后台作业接入。

### 4.5 Prompt 版本化（低成本，工程化收益）
现状：提示词散在 `PlannerNode/VerifierNode/AggregatorNode` 和 `application.yml`，改动不可追溯。
建议：给提示词加不可变版本（base revision + 覆盖 + 冲突拒绝），记录"这次 run 用的哪版"。落点：`prompt_*` 表 + run 记录 prompt 版本。

### 4.6 Outbox/Effects 模式（可选，仅在加"主动/外部回写"时）
现状：幂等账本只覆盖工具调用；媒体任务与消息落库是两条路径。
建议：一旦要加通知/外部回写，用"事务内入队 + commit 后执行 + 同 key 去重 + 指数退避"的 effects 队列，避免"消息发了但库没写"。

## 5. 不建议照搬的

1. **多来源/多渠道/身份不互通**的完整度：对单团队单仓库是合理复杂度，对 Aster（通用创作+智能体平台）过重。
2. **只有单 worker 模型**：它的"多智能体"其实是 Harness 的 subagent，没有我们这种"规划→分波并行→质检→返工"。
   我们的并行规划是差异点，不要换成它的单执行器模型。
3. **领域术语密度**：Ticket/Assignment/Mandate/Disposition/Attention 对内部平台是可读的，对普通用户太重；
   Aster 的对外文案应保留"任务/步骤/计划"这类词，仅在内部模型里用这些概念。
4. **SQLite 单文件 + 目录锁**：它单机常驻，Aster 是多容器 + PostgreSQL；只借模式（generated column 只读活跃集、schema 单向迁移），不借实现。

## 6. 建议的落地顺序

| 步骤 | 内容 | 预计改动面 |
|---|---|---|
| Step 1 | run 阶段语义化（4.1）+ 结构化等待原因（4.3） | `agent_run` 迁移 + 状态映射 + 任务列表/面板分组 + 一个 `humanAction` 工具 |
| Step 2 | Ticket/Assignment 分离（4.2）+ 后台作业配额（4.4） | 新表 + 恢复逻辑改造 + 媒体任务接入 |
| Step 3 | Prompt 版本化（4.5）→ 需要时再加 effects 队列（4.6） | 提示词表 + run 记录 + 面板（可选） |
| 暂缓 | Source/Policy 主动层（Eval 准入、Mandate）：等真的有"外部事件触发"需求再做 | — |

## 7. 参考

- 仓库：https://github.com/deepseek-ai/dsh-superagent-prototype （docs/000~008）
- 宿主平台：https://github.com/deepseek-ai/deepseek-harness
- 配套文章：*Exploring Super-agent Design: Towards a More Autonomous Agent System*（README 引用）
