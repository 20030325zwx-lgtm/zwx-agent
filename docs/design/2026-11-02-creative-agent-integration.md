# M1.5 智能体与创作模块融合：详细设计

- 日期：2026-11-02
- 状态：待实施设计；本文中的新表、接口、组件与配置名称均为拟议契约。
- 核对基线：`73d1a48`，现有迁移至 V19；提交存在不等于所有场景已验收。
- 上游：[核心产品规划](./2026-11-02-ai-creative-core-product-plan.md)、[UI 规范](./2026-11-02-ai-creative-ui-visual-design.md)、[M1 实施记录](./2026-11-02-m1-project-workspace.md)。
- 本批目标：把首页一句想法转成真实、可编辑、可追溯的作品草稿，并能在同一作品中继续与助手协作。

## 1. 产品决策

首页“想做点什么”接入创作助手。用户提交想法后，助手理解作品类型和要求，生成结构化方案，再由服务端保存为创作项目及其草稿内容。用户进入项目后能看到产物、过程、假设和继续创作入口。

保留“新建作品”作为手动入口。图片、视频、小说表示作品类型，不和 Agent 并列为同一种分类。首页主流程采用智能创作，手动新建不调用模型。

M1.5 第一版自动完成草稿创建；图片、视频生成仍通过现有明确的生成动作触发。下一阶段才加入助手发起媒体生成和批次的能力。这样第一版即可实现用户所说的“指令发给智能体，智能体自动创建”，产物不是聊天里的一段建议。

### 1.1 参考产品事实

本会话实际查看了幻映 AI 已登录首页：存在视频生成、图片生成、文本生成、Agent 四种模式；Agent 模式说明“使用画布里的同一个 Agent，结合素材、技能与创作上下文协作”，并注明生成与修改仍经过审批和额度检查。

这能证明其产品入口区分直接生成和 Agent 协作，不能证明所有生成都经过 Agent，也不能证明其服务端架构。Aster 使用现有项目工作区承载协作，不以先建设自由画布为前置条件。

### 1.2 范围与交付顺序

| 阶段 | 自动能力 | 完成定义 |
| --- | --- | --- |
| A：图片最小闭环 | 理解想法、建立项目、保存图片提示词草稿 | 刷新后可继续编辑，重试不重复建项目 |
| B：视频与小说草稿 | 视频首集/场次/镜头；小说设定/章节大纲 | 结构化内容出现在现有工作区 |
| C：项目内协作 | 读取上下文、提出局部修改、用户采纳 | 不覆盖用户并发编辑，保留修改来源 |
| D：受控生产 | 助手提交媒体任务、视频批次、章节工作流 | 已授权参数与实际提交一致，结果可追溯 |

A+B 是 M1.5 首版发布范围；C 为紧接迭代；D 单独验收。M2 素材溯源可与 C 并行推进，技能市场、自由画布、完整成片不进入本批。

## 2. 当前能力与明确缺口

| 当前依据 | 已有能力 | 本次使用方式/缺口 |
| --- | --- | --- |
| `CreativeHome.vue:startFromIdea` | 创建项目；图片额外创建草稿；跳转项目 | 替换智能入口，保留手动新建；当前无 Agent 调用 |
| `ProjectShell.vue` 与项目子路由 | 剧情、分镜、设定、章节、素材、任务 | 增加创作过程入口与助手面板 |
| `ManusAgentController` | 会话、运行、模型解析、SSE、取消/恢复 | 抽取可复用启动服务，避免新 Controller 调旧 Controller |
| `AgentRunService` / `GraphCheckpoint` | 运行记录、节点检查点、部分结果 | 保留 Agent 事实记录；创作状态另建关联，不硬塞入旧图结构 |
| `ToolConfirmationService` / 工具账本 | 工具级确认、调用结果复用 | 复用基础能力；不能视为已有创作计划授权或跨运行去重 |
| `CreativeImageTool` | 异步 `generateImage`，来源关联 Agent run | D 阶段适配；A/B 不注册此工具 |
| `CreativeProjectService` | 创建 ACTIVE 项目、归档、版本、小说设定 | 项目不新增 DRAFT 状态；草稿属于内容层 |
| `StoryContentService` | 剧集、场次、镜头、角色、地点等领域写入 | 通过原服务写入，新增事务应用与幂等映射 |
| `NovelPlanningService` | 下一章方向建议 | 可复用上下文构建思路，不等同于整本大纲生成 |
| `NovelChapterService.saveFromRun` | 同一工作流运行对应一章草稿 | 不用同一 Agent runId 批量保存多章 |
| `MediaJobService` / 视频批次 | 异步生产、候选、素材回写 | 保持单一媒体执行来源 |

M0、M1 主体、UI 令牌收敛和素材卡修正已提交。M1 记录明确：未完成所有真实生成、390px 和 200% 缩放验收；M2 的业务分类和完整引用反查尚未完成。不能把本批立项建立在“所有前序功能已完全验收”上。

两个接入风险必须消除：

1. `agent_conversation.project_id` 通过 `ProjectWorkspaceService` 校验，语义为编程工作区。创作项目不得写入该字段。
2. `CreativeImageTool.ensureDefaultProject()` 当前选取列表内首个 ACTIVE 项目，不保证是当前创作作品。创作入口必须由服务端绑定项目，禁止回退到该逻辑。

## 3. 用户体验与页面行为

### 3.1 首页

保留标题、想法输入、作品类型和最近作品。主按钮可用“智能创建”，点击后展示“正在准备作品”，收到运行标识即进入项目页，不让首页长期等待完整模型输出。

说明文案改为：“AI 将生成可编辑草稿；文字规划会调用模型，图片和视频生成另行确认。”现有“创建作品不会计费”只适用于手动新建，不能继续用于智能创建。

首版类型必须由用户选择；默认值沿用当前选项。若输入明确与选定类型冲突，如选图片却要求十章小说，先返回一个简短澄清问题，用户选择后才固定项目类型。普通缺失信息使用可见假设，避免连续追问。

A 阶段只开放已实现类型的智能入口；其他类型仍能手动新建，不能提交后才告诉用户不支持。

### 3.2 新项目内

项目页显示“创作过程”：正在规划、校验、保存、已创建草稿，附真实产物数量和下一步。长耗时阶段显示阶段文字和耗时，不编造百分比。

| 类型 | 自动保存内容 | 完成后定位 |
| --- | --- | --- |
| 图片 | 项目名称/描述、原始想法、有效提示词草稿 | `studio`，加载创建出的 draftId |
| 视频 | 首集剧情、少量场次、镜头提示词、必要角色/地点草稿 | `storyboard`，可切回 `story` 编辑 |
| 小说 | 人物/世界观/文风、章节标题与剧情走向大纲 | `chapters`，大纲单独区分于正文 |

创建成功的标准是领域事务已提交。Agent 说“完成了”不能触发成功状态。列表刷新依赖后端返回的产物 ID 与事件。

### 3.3 项目内助手（C）

在 ProjectShell 中增加“创作助手”开关，面板显示当前作品和所选镜头/章节。助手与 ShotInspector 复用右侧空间，通过切换标签打开，不同时挤出两列检查器。

允许用户说“把第二场改为清晨”“为第三个镜头提出两个版本”。返回变化摘要、旧值/新值和采纳动作。已确认剧情、已采用候选不自动覆盖。选择对象只是上下文，不是修改授权。

会话刷新可恢复；改变项目必须切换绑定会话。界面展示“已生成建议”与“已保存修改”的差别。选择性采纳必须按对象生成新的应用记录，不通过解析聊天 Markdown 回写。

### 3.4 失败与取消

模型失败保留原输入和项目入口，提供重试、手动编辑。写库失败保留有效计划，允许只重试保存，不重复调用模型。取消后保留已提交的草稿，明确展示已完成的部分。

新项目未产生内容时允许用户归档；不自动删除项目。返回首页的最近作品仍可找到这次创建过程。

## 4. 架构与复用边界

建议新增 `creative/agent/` 包，采用领域编排层连接现有 Agent 运行基础与创作服务。

| 拟议组件 | 职责 |
| --- | --- |
| `CreativeAgentController` | 身份、请求校验、命令与查询接口 |
| `CreativeAgentApplicationService` | 创建 session/request、项目绑定、启动/取消/恢复 |
| `CreativePlanningService` | 构建上下文、选择模型、取得结构化计划 |
| `CreativePlanValidator` | Schema、引用、类型、数量、业务约束校验 |
| `CreativePlanApplyService` | 在短事务中幂等应用有效计划 |
| `CreativeContextBuilder` | 读取明确范围内的项目、参考、版本快照 |
| `CreativeRunRepository` / `CreativeRunDispatcher` | 持久命令、租约、运行阶段、恢复 |
| `CreativeAgentToolFactory` | C/D 阶段的创作专用工具白名单 |
| `AgentRunLauncher` | 从现有 Controller 抽取身份、模型、运行、事件等公共启动能力 |

第一版规划是有边界的结构化任务，使用一次模型规划与最多一次格式修复即可。复用会话、AgentRun、模型解析、用量记录和执行资源；不强制走通用 Planner→Workers→Verifier 多轮图流程。C/D 在确有多步工具需求时复用图编排。

Agent 负责提出内容，Validator 负责判定可应用，ApplyService 负责持久化。模型不得输出 SQL，也不能靠文件写入工具间接修改数据库。

新入口注册 `creative` 执行上下文或等价的明确 scope，避免进入通用闲聊短路。现有依赖 `super` 的查询、恢复、记忆和工具 Hook 必须逐项审计；兼容前不得简单更改 agentKey 上线。

## 5. 身份与项目关联

采用独立 `creative_agent_session` 关联表，避免重载编程工作区字段。

首版一个创作项目对应一个助手会话，一个会话内多个请求；每个请求可有多个模型执行尝试。所有请求携带已鉴权的 tenantId/userId；模型只接收必要上下文，不能指定执行身份。

每次读取、订阅、取消、恢复、应用计划和媒体提交都重新验证归属。跨项目引用须通过素材权限校验。对 UUID 格式合法的对象也必须进行业务归属检查。

## 6. 拟议数据模型

所有新表含 `tenant_id`、`user_id`、`created_at`、`updated_at`；下列字段为逻辑设计，迁移实施时根据既有 ID 类型生成 DDL。当前最新为 V19，暂定下一版本 V20，合并前重新检查序号。

### 6.1 `creative_agent_session`

| 字段 | 含义 |
| --- | --- |
| id UUID | 创作会话标识 |
| project_id UUID | 关联 creative_project |
| conversation_id | 关联已有 agent_conversation，类型沿用已有主键 |
| version bigint | 会话并发版本 |

唯一约束：项目归属范围内 project_id 唯一；conversation_id 唯一。归属外键或等价校验保证项目、会话、session 同租户同用户。会话删除使用受控服务，不能因聊天清理级联删除作品或计划证据。

### 6.2 `creative_agent_request`

| 字段 | 含义 |
| --- | --- |
| id UUID / session_id UUID | 用户一次创作意图 |
| client_request_id / request_hash | 请求去重及参数一致性校验 |
| input_json / kind / operation | 原始想法、类型、CREATE/PROPOSE_UPDATE |
| status / phase | 创作业务状态与展示阶段 |
| current_agent_run_id / attempt | 当前模型尝试与次数；保留历史尝试映射 |
| context_snapshot_json | 实际使用的上下文和基准版本 |
| model_snapshot_json | 脱敏模型标识、版本/参数及模板版本 |
| lease_owner / lease_until / execution_epoch | 执行归属、超时和旧执行者隔离 |
| version / cancel_requested | 命令并发与取消标记 |
| error_code / error_summary | 可展示错误，不包含供应商密钥或请求头 |

唯一约束 `(tenant_id,user_id,client_request_id)`；请求哈希包括类型、想法、参考和模式。同一键同一参数返回原 request/project；同一键不同参数返回 409。不能仅依赖前端按钮禁用。

待执行 request 本身作为持久工作队列，事务提交后由 dispatcher 领取；内存执行器只是运行载体。无需为首版新增消息中间件。

### 6.3 `creative_agent_plan`

字段：id、request_id、revision、schema_version、payload_json、payload_hash、validation_errors_json、status、applied_at、result_manifest_json。

唯一约束 `(request_id, revision)`。每个 request 最多一个已应用的 CREATE 计划；plan revision 不可原地改写，修订产生新版本。应用状态只能由服务端改变。

### 6.4 `creative_agent_artifact`

字段：plan_id、local_key、entity_type、entity_id、entity_version_at_apply。唯一约束 `(plan_id,local_key)`；记录逻辑产物到真实对象的映射，支持刷新、追溯和幂等恢复。

result_manifest 返回创建的项目、草稿、剧集、场次、镜头、大纲 ID 及路由；不能只存自然语言“创建成功”。

### 6.5 小说大纲适配

新增 `creative_novel_outline_item`（拟议）：project_id、ordinal、title、plot_direction、status、source_plan_id、local_key、chapter_id 可空、version。状态为 PLANNED/LINKED；ordinal 项目内唯一。

大纲不是章节正文，不把空正文塞进已有章节库。执行“写这一章”时显式调用现有章节工作流，完成后关联真实 chapterId。保留工作流 `source_run_id` 的原语义，不伪造工作流 ID；同一大纲项重复提交的去重在 D 阶段补齐。

### 6.6 持久事件

新增创作事件表或扩展现有事件存储，以 `(request_id,sequence)` 唯一。事件含类型、阶段、对象引用、时间、脱敏摘要。每次业务状态提交同时记录事件，断线后按序号补拉。不能只依赖进程内 SSE 缓冲。

## 7. 状态、事务和恢复

### 7.1 创作请求状态

| 状态 | 后续转换 | 说明 |
| --- | --- | --- |
| QUEUED | PLANNING / CANCELLED | 已持久化等待领取 |
| PLANNING | NEEDS_INPUT / VALIDATING / FAILED / INTERRUPTED / CANCELLED | 模型调用，可有用量 |
| NEEDS_INPUT | QUEUED / CANCELLED | 用户补充同一请求，记录输入 revision |
| VALIDATING | APPLYING / AWAITING_ACCEPTANCE / FAILED / CANCELLED | 校验结构和领域约束 |
| AWAITING_ACCEPTANCE | APPLYING / CANCELLED | C 阶段已有内容修改需采纳 |
| APPLYING | COMPLETED / FAILED | 短事务落库，完成后才展示成功 |
| INTERRUPTED | QUEUED / CANCELLED | 租约失效，可恢复 |
| FAILED | QUEUED / APPLYING / CANCELLED | 模型失败重规划；有效计划只重试落库 |
| COMPLETED / CANCELLED | 无 | 终态；新意图创建新请求 |

CREATE 新草稿按用户点击智能创建的意图自动应用；PROPOSE_UPDATE 进入待采纳。AgentRun 执行完成不等于创作请求完成，媒体任务完成也不等于镜头已采用。

### 7.2 事务边界

T1：写请求去重记录、ACTIVE 项目、会话关联与 QUEUED 状态；同库短事务，失败全部回滚。类型澄清若发生在建项前，先保存无项目的入口请求，再在用户确认后事务绑定项目/session；实现时允许 request.session_id 在 NEEDS_INPUT 阶段为空，禁止进入执行阶段仍为空。

模型调用在事务外进行。领取工作时递增 execution_epoch，心跳续租。持久化模型结果必须携带 epoch，旧执行者结果不得覆盖新尝试。

T2：锁定 request/plan 和项目，核验身份、epoch、取消标记、计划未应用及基准版本；按依赖顺序调用领域服务，写 artifact 映射、结果清单、COMPLETED 和事件，同一事务提交。

领域服务不得使用独立提交破坏 T2 原子性。批次规模上限保证事务短小；不得在 T2 内调用外部模型、存储下载或供应商生成接口。

### 7.3 并发与恢复规则

- 一个 session 同时只允许一个活动请求；由数据库约束/锁实施，不只使用 JVM 锁。
- 项目编号分配在项目锁下完成；若手动路径也分配集号/章号，相关路径必须遵守相同锁或唯一约束重试。
- 取消与应用都竞争同一 request 锁：取消先提交则不落库；应用先提交则返回已完成，取消不声称撤回已保存内容。
- 服务重启：租约过期的非终态请求进入 INTERRUPTED；已验证计划优先重试保存；模型阶段重试可能再次产生文字模型费用，界面说明。
- T2 已提交但响应丢失：查询/重试返回原产物，不再次创建。
- 旧 AgentRunService 的检查点存在不代表已有跨进程 fencing。新增创作写入必须受 request epoch 保护；共用运行层的必要兼容改动是发布前置。
- 本地与云端共用数据库时，只允许预定执行环境领取，或以明确环境分区领取；不得让关闭现有 scheduler 的本地实例因新增 dispatcher 意外开始执行。

## 8. CreativePlan v1 契约

通用字段：schemaVersion、kind、projectProposal、assumptions、questions、references、content。模型不生成数据库主键、用户身份、权限、运行成功状态或可执行 URL。

示例为 VIDEO 最小有效计划，不是已有 API 响应：

```json
{
  "schemaVersion": "1",
  "kind": "VIDEO",
  "projectProposal": {"name": "雨夜的怀表", "description": "车站中的一次神秘交接"},
  "assumptions": ["按一集短片草稿规划，偏悬疑风格"],
  "questions": [],
  "references": [],
  "content": {
    "characters": [],
    "locations": [],
    "episodes": [{
      "key": "episode-1", "title": "交接", "goal": "建立悬念",
      "synopsis": "女孩在列车到站前交出怀表。", "script": "",
      "scenes": [{
        "key": "scene-1", "title": "雨夜站台", "locationText": "站台",
        "timeOfDay": "夜晚", "charactersText": "女孩、陌生人",
        "goal": "完成交接", "conflict": "列车即将进站",
        "entryState": "双方尚未接触", "exitState": "怀表已交出",
        "shots": [{
          "key": "shot-1", "purpose": "交代环境", "action": "女孩站在雨棚下",
          "camera": "远景缓慢推进", "dialogue": "", "continuity": "雨夜",
          "durationTargetSeconds": 5,
          "promptText": "雨夜车站，女孩站在雨棚下，远景缓慢推进"
        }]
      }]
    }]
  }
}
```

IMAGE content：originalPrompt 由服务端取原始输入，模型提供 effectivePrompt 和 parameterSuggestions。首版仅将提示词持久化为既有图片草稿；建议尺寸等存在 plan，生成前通过模型能力校验后再带入表单。

NOVEL content：settings 仅包含现有 `characters/worldview/style`；outline 为 key/title/plotDirection 列表。章节正文不在 A/B 自动生成。

VIDEO content：字段映射到 EpisodeRequest、SceneRequest、ShotRequest；characters/locations 映射 ResourceRequest。所有生成内容按 DRAFT 写入，不自动调用 confirm 或 select。

### 8.1 校验与限制

- 严格按 kind 分支解析；拒绝未知 schemaVersion、未知命令字段、类型错配和重复 key。
- 初始建议限制：输入 4000 字符；视频 1 集、最多 4 场、12 镜头；角色/地点各 8 个；小说最多 10 个大纲项；计划序列化最大 256KB。上限属于拟议产品约束，领域字段还需分别遵守现有校验。
- 不通过静默截断让计划“通过”；超限应要求模型缩小范围或返回具体错误。
- UUID 参考必须来自用户选中的授权资产；仅凭文件名、描述不得视为看过图像，未做视觉分析必须注明。
- 时长只是叙事目标，不等同于供应商支持时长；模型能力在真正生成前再次校验。
- 最多一次格式修复，保留原始解析错误摘要与模板版本；仍失败则终止，不无限自我重试。
- 新项目写入一切 id 由服务端生成，local key 只用于本次计划内的引用和 artifact 映射。

## 9. HTTP 与事件接口（拟议）

以下路径相对现有 `/api` 服务前缀。

| 方法与路径 | 用途 |
| --- | --- |
| POST `/ai/creative/agent-runs` | 创建创作请求，返回 202 |
| GET `/ai/creative/agent-runs/{requestId}` | 读取业务状态、阶段、产物、可执行动作 |
| GET `/ai/creative/agent-runs/{requestId}/events?afterSequence=` | 订阅/回放持久事件，支持 Last-Event-ID |
| POST `/ai/creative/agent-runs/{requestId}/answers` | 回答澄清问题，带 expectedVersion |
| POST `/ai/creative/agent-runs/{requestId}/cancel` | 取消，幂等 |
| POST `/ai/creative/agent-runs/{requestId}/retry` | 服务端选择规划或应用恢复点 |
| GET `/ai/creative/projects/{projectId}/agent-session` | 取绑定会话与当前请求 |
| POST `/ai/creative/projects/{projectId}/agent-messages` | C 阶段提交修改意图 |
| POST `/ai/creative/agent-plans/{planId}/accept` | C 阶段采纳指定版本建议 |

创建请求：

```json
{
  "clientRequestId": "客户端为一次提交生成并保留的 UUID",
  "idea": "雨夜车站，一个女孩把怀表交给陌生人",
  "kind": "VIDEO",
  "referenceAssetIds": [],
  "planningModelRowId": null
}
```

首版不提供含义模糊的 AUTO 模式参数，接口语义即“自动创建草稿”。planningModelRowId 可空，服务端解析当前用户可用对话模型并记录快照；不接受密钥、任意 endpoint 和未经授权模型。

正常受理返回 requestId、sessionId、conversationId、projectId、status、phase、version、eventsUrl。requestId 是创作意图 ID，不冒充底层 Agent runId；Agent 尝试尚未启动时 runId 可空。类型待澄清响应允许 session/project 为空，并附 questions。

GET 额外返回 `artifacts`、`assumptions`、`nextActions`、`error` 和 `usage`。前端只渲染服务端许可的 nextActions。

事件类型：request.created、phase.changed、input.required、plan.validated、artifact.applied、request.completed、request.failed、request.cancelled。事件必须有递增 sequence 与 requestId；断线重连只订阅，不重新执行 POST。回放窗口过期返回重取快照提示，前端先 GET 再订阅。

错误：400 输入无效；403 无权限；409 重复键参数不一致/版本冲突/会话忙；422 计划或模型能力不满足；429 配额；503 执行功能关闭或服务不可用。模型解析失败作为运行错误记录，避免把异步失败伪装成创建接口成功完成。

## 10. 模型、工具与费用

规划模型沿用模型目录与 DynamicChatModelFactory，不另建 API Key 设置。图片/视频模型继续由 MediaModelResolver 按用途解析，规划模型不替代媒体模型。

费用分成文字规划与媒体生成：点击智能创建会调用文字模型，可能产生费用；创建项目这一步数据库操作本身没有模型费用。展示真实 token 用量，供应商未提供价格时显示“费用未知”，不用 0 元代替。

A/B 的工具集不包含生图、生视频、终端、数据库查询、代码写入和全部 MCP。只读项目上下文可由服务端注入。不得通过通用超级智能体的全量工具装配意外扩大权限。

D 阶段工具建议：读取项目摘要、提交局部修改建议、提交图片任务、提交镜头候选、创建视频批次、启动指定章节工作流。写操作均委托领域服务，返回真实 jobId/batchId/chapterId 或结构化错误。

媒体授权绑定计划 revision/hash、对象范围、模型、参数、数量、最大费用（可获得时）和有效期；任一实质变化使授权失效。现有按工具名确认需要补参数范围约束，不能用“已允许 generateImage”代表无限次生成授权。

幂等键从持久 actionId 派生并跨重启复用；禁止每次调用使用随机 UUID 作为同一业务动作的媒体键。供应商请求结果未知进入既有调和路径，不因 Agent 重试直接再生成。任务进度由服务端调度与前端订阅/轮询取得，模型不反复调用查询工具等待结果。

## 11. 前端实施映射

| 现有文件/拟议组件 | 改动 |
| --- | --- |
| `frontend/src/views/creative/CreativeHome.vue` | startFromIdea 改为持久创作命令；保留 clientRequestId；区分手动和智能创建文案 |
| `frontend/src/api/index.js` | 增加上述命令、查询、订阅封装 |
| `project/ProjectShell.vue` | 持有 session/request 状态，挂载运行入口及助手面板 |
| `project/composables.js` | 增加 useCreativeAgent，统一快照、SSE 补拉与降级轮询 |
| 拟议 `CreativeRunPanel.vue` | 阶段、假设、澄清、错误恢复、产物链接 |
| 拟议 `CreativeAssistantPanel.vue` | 项目内多轮协作与修改建议采纳 |
| `ProjectStudioTab.vue` | 按 draftId 加载自动创建的草稿 |
| `ProjectStoryTab.vue` / `ProjectStoryboardTab.vue` | 应用事件后刷新现有共享 workspace，不另存一份镜头数据 |
| `ProjectChaptersTab.vue` | 显示大纲项与正文状态差别，进入已有写章流程 |

SSE 不可用时每 3 秒查询快照，终态停止；切项目卸载订阅。artifact.applied 事件在事务提交后可见，前端去重处理，不把流式 JSON 碎片显示为已保存对象。

助手面板宽 360–420px，窄屏覆盖打开；关闭面板不取消运行。复用现有字体、状态和按钮令牌，不引入第二套聊天页面样式。

## 12. 项目内修改与上下文（C）

上下文默认包含项目类型/设定、当前对象、相邻必要内容、引用资产、真实状态和版本；不默认注入全部历史或知识库。来源文本和附件内容视为数据，不能改变工具权限。

计划保存 baseVersions 或字段快照哈希。对已有镜头可使用当前 version；对没有可用版本的对象，在事务中锁定并比较应用前快照。只有项目 version 不足以检测子对象修改，不能用它替代所有并发控制。

采纳时校验旧值、状态和引用。冲突返回 409 并展示需重新生成建议的对象。首版局部计划采用整份采纳/放弃；选择性采纳延后，避免依赖断裂。用户已经确认的内容只能在显式采纳后更新，并保留前后快照。

## 13. 实施任务与发布门槛

| 批次 | 任务 | 发布门槛 |
| --- | --- | --- |
| A0 | 契约 DTO、数据迁移、请求去重、项目会话绑定、领取/租约 | 重复提交与两个实例竞争无重复项目 |
| A1 | 公共 Agent 启动能力、图片规划、严格校验、事务应用 | Mock 模型下完整图片草稿链路与故障恢复通过 |
| A2 | 首页入口、运行面板、刷新恢复、手动入口 | 浏览器真实状态、错误和断线验收通过 |
| B1 | 视频结构化计划与领域适配 | 1 集多场多镜头原子保存、草稿不自动确认 |
| B2 | 小说大纲模型/页面与工作流衔接入口 | 不覆盖正文，不混淆 run 来源 |
| C | 项目内助手、版本冲突与建议采纳 | 并发修改、跨项目隔离验收通过 |
| D | 计费工具、参数授权、媒体去重 | Mock 生产验证后，再做明确限量的真实生成验收 |

功能开关建议分别控制入口和执行领取：入口关闭不影响已存在结果查看；执行关闭停止领取新任务，在途请求按明确策略完成或中断。新增开关默认关闭，开启前确认本地/云端执行归属。

灰度先对测试账号开启 A，再 B；C/D 独立开关。关闭新功能后首页恢复原手动链路；保留新增数据和只读查询，不回滚删除业务数据。数据库为增量迁移，不修改历史 V1–V19。

## 14. 验收矩阵

| 场景 | 必须观察到的结果 |
| --- | --- |
| 图片一句话 | 同一项目内有原始输入、有效提示词和可编辑草稿，未创建 MediaJob |
| 视频一句话 | 剧集/场次/镜头关联正确，均未人工确认或选片 |
| 小说一句话 | 大纲与正文分开，人物/世界观/文风可编辑 |
| 连点/超时重发 | 同 requestId/projectId，同键不同参数 409 |
| 模型非法 JSON/超限 | 最多一次修复，无部分内容落库 |
| 模型超时 | 可重试，保留输入，展示可能发生的规划用量 |
| T2 中途数据库异常 | 领域产物与 artifact 全部回滚；保留有效计划 |
| T2 提交后网络断开 | 刷新得到既有产物，不重复建集/镜头 |
| 两进程/租约失效 | 仅当前 epoch 可持久化计划与产物 |
| 取消与应用同时发生 | 明确唯一结果，不出现“取消成功但新增草稿” |
| 会话/项目归属伪造 | 所有查询、事件和写入被服务端拒绝 |
| 手动编辑后采纳旧计划 | 409 冲突，不静默覆盖 |
| 项目归档 | 阻止新应用，保留历史可查看 |
| SSE 重放/重复事件 | 无重复条目、无重复业务写入 |
| 编程超级智能体回归 | 原工作区绑定、权限、模型选择、恢复保持可用 |
| 真实模型小规模验收 | 输出结构合规、质量可编辑；记录调用次数和费用来源 |

测试使用隔离数据库和 Mock 供应商验证状态/事务，不默认接当前本地配置中的云数据库。真实规划与媒体生成分开验收，媒体测试需明确数量预算。

UI 验收覆盖 1440/1280/1024 宽、窄屏覆盖面板、深浅主题、长标题、空结果、失败、刷新和返回；M1 未覆盖的移动/缩放不能在本批宣称已经通过。

## 15. 关键决策记录与待确认项

已确定的设计选择：独立创作 session 关联、持久 request 队列、服务端结构化校验、短事务应用、ACTIVE 项目承载草稿、新项目自动应用、已有内容修改显式采纳、媒体执行独立授权。

本设计修正上轮讨论中的两点：不创建不存在的 DRAFT 项目状态；不将一次 Agent run 当作多个小说章节的 source_run_id。文字规划会调用模型，首版不能宣传为免费自动创建。

实施前仍需通过代码或实验确认：

- 公共 Agent 启动服务支持 creative scope 的具体改动范围，特别是恢复、记忆和用量 Hook。
- 当前对话模型是否原生支持 JSON Schema；不支持时采用严格解析与有限修复，不能承诺模型端强约束。
- 既有对话/运行删除规则与新关联外键的兼容方案。
- 各领域服务事务传播和编号分配是否满足 T2，一致锁协议需覆盖手动创建路径。
- 文字模型调用上限、租约 TTL、心跳间隔和上下文 token 上限通过实测确定；首版拟采用 120 秒租约、20 秒心跳，外部调用必须有独立超时。

以上待确认项由实施任务解决，不能用猜测替代验收。完成 A+B 后的交付示例是：输入“雨夜车站的悬疑短片”，进入已有项目工作区，看到首集、场次和可编辑镜头草稿，并能继续使用现有生成与选片能力。
