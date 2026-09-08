# 智能体长期记忆、技能仓库与统一工作区

> 状态：设计提案，尚未实施
>
> 目标：在 design/plans/08 的 hook 管线轨道上，补齐 AgentScope Harness 剩余三项能力——分层长期记忆、markdown 技能仓库、统一工作区。原则：**新能力 = 服务类/存储 + hook 挂载**，核心执行类（BaseAgent/ToolCallAgent）零改动。

## 1. 背景与现状

### 1.1 记忆现状

| 层 | 现状 | 问题 |
| --- | --- | --- |
| 工作记忆（run 内） | `BaseAgent.messageList` 内存增长 | 无压缩，长 run 撑爆上下文、烧 token |
| 会话记忆 | LoveApp `budgetHistory` 8000 字符尾截断；manus `historyContext` 最近 20 条（PostgresChatMemory） | 截断丢信息，仅窗口 |
| 长期记忆 | **无** | 用户偏好/事实跨会话全丢 |

### 1.2 技能现状

- `BuiltInSkillRegistry` 硬编码（唯一技能 web-research）；`SkillConfigurationService` 提供租户级开关（表 agent_skill_configuration）；`SkillPromptBuilder` 拼 system prompt 段落。
- 加一个技能必须改 Java 代码发版；无 markdown 文件、无自动加载、无自我进化。

### 1.3 工作区现状

- `ToolSandbox.scopeDir(scope)` → `temp/tools/{scope}`；`ToolFactory.createTools(scope)` 把 workDir 给 FileOperationTool / ResourceDownloadTool（download 子目录）/ PDFGenerationTool（pdf 子目录）。
- scope 语义混杂（conversationId 或 "shared"）；`GET /manus/files` 直接 resolve 下载；无文件清单、无跨会话共享语义。

## 2. 目标与非目标

### 2.1 目标

- 工作区：`{tenant}/{agent}/{conversation}` 三级目录模型 + WorkspaceService 收口 + 文件清单端点，三工具路径来源统一；
- 技能：markdown 文件 + front-matter 元数据 + 目录扫描 + reload；租户开关复用现有表；内置技能迁移为文件；
- 记忆：三层齐备——beforeThink 压缩保护工作记忆；事实日志新表 + LLM 提取（异步、可开关）+ 注入；
- 记忆压缩/事实提取/注入对模型与核心类的接入全部经 hook 或既有 preparation 拼装点，无新散落逻辑。

### 2.2 非目标

- 不做事实的向量语义检索（第一阶段最近 + 关键词匹配，表结构预留扩展）；
- 不做技能文件监听热加载（提供 reload 端点）；
- 不做事实冲突消解（同 fact 覆盖更新 last_seen_at）；
- 不做分布式文件同步（单机目录，compose 部署挂卷即可）。

## 3. 方案设计

### 3.1 统一工作区（批次 A）

目录模型：

```text
{app.workspace.root:temp/workspaces}/{tenantId}/{agentKey}/{conversationId}/
    file/        文件工具读写根（FileOperationTool）
    download/    下载资源（ResourceDownloadTool）
    pdf/         产出文件（PDFGenerationTool）
{app.workspace.root}/{tenantId}/{agentKey}/shared/    跨会话共享（读写）
```

子目录名沿用既有工具约定（file/download/pdf），工具类零改动。

- 新增 `workspace/WorkspaceService`：
  - `workDir(tenantId, agentKey, conversationId)`、`sharedDir(tenantId, agentKey)`、`resolveWithin(base, relative)`（吸收 ToolSandbox 的穿越/符号链接校验）；
  - `listFiles(conversationId)`：相对路径 + 大小 + 修改时间。
- `ToolFactory.createTools(scope)` 改为从 WorkspaceService 取 workDir（scope=conversationId 时需 tenant/agent 上下文——扩展签名为 `createTools(tenantId, agentKey, conversationId)`，调用方在 AiController/ManusGraphOrchestrator 已有这三元组）；
- 下载端点：`GET /manus/files` 切换到 WorkspaceService（保留旧 temp/tools 路径回退读取，兼容历史会话）；新增 `GET /workspace/files?conversationId=` 清单端点；
- 兼容：旧目录不迁移（工具产物属临时数据），新会话自动落新结构。

### 3.2 markdown 技能仓库（批次 B）

技能文件 `{app.skills.dir:skills}/{id}.md`：

```markdown
---
id: web-research
name: 联网查询
agents: [love, travel, test, super]
tools: [webSearch]
trigger: 用户询问天气、交通、价格等时效性事实
---
正文 = 给模型的操作指引（注入 system prompt）
```

- 新增 `skills/SkillRepository`：启动扫描目录、解析 front-matter（自写轻量 `key: value` + 数组解析，不引新依赖）、`reload()`；**目录为空或解析失败时回落现有硬编码 web-research**，保证零技能目录也能跑；
- `SkillPromptBuilder` 改为从 SkillRepository 取技能（租户开关叠加逻辑不变），prompt 模板不变；`BuiltInSkill` record 复用为统一模型（markdown 技能转成同 record）；
- 端点：`GET /skills/catalog` 增加 source 字段（markdown/builtin）；`POST /skills/reload`（ADMIN）；
- 自我进化（后续批次）：`SaveSkillTool` 让 agent 把新技能写成 markdown 存回目录（本期不实现，仅目录结构预留写权限）。

### 3.3 分层长期记忆（批次 C）

**L1 工作记忆压缩**——`MemoryCompressionHook`（order=250，转换型，beforeThink）：

- 字符预算 `app.agent.memory.context-budget:24000`：messageList 总字符超限 → 取最旧的消息块（保留最近 `recent-keep:6` 条）调 LLM 压成一条摘要 UserMessage 替换原块；
- LLM 失败降级为直接丢弃最旧块（保最近 6 条）；system prompt 由框架单独传，不在压缩范围；
- 只对 ReAct 长任务有意义（manus worker maxSteps=8 天然短），主要为后续长 run/其他 agent 服务，开关默认开、预算可调。

**L3 事实日志**——V7 migration：

```sql
CREATE TABLE agent_memory_fact (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    user_id VARCHAR(64),
    fact TEXT NOT NULL,
    fact_hash CHAR(64) NOT NULL,              -- sha256(normalized fact) 防重
    source_conversation_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_agent_memory_fact ON agent_memory_fact (tenant_id, agent_key, fact_hash);
CREATE INDEX idx_agent_memory_scope ON agent_memory_fact (tenant_id, agent_key, created_at DESC);
```

- 新增 `memory/MemoryService`：
  - `extractAsync(tenantId, agentKey, conversationId, userPrompt, answer)`：LLM 抽取 0~3 条事实 JSON → upsert（冲突更新 last_seen_at）；开关 `app.agent.memory.extraction-enabled:false`（成本考虑，按需开启）；
  - `factsFor(tenantId, agentKey, userPrompt, limit)`：最近优先 + prompt 关键词 like 命中加权，返回 top 5。
- 接入点（与技能同理，诚实对待 hook 边界）：
  - ReAct 链路：`MemoryExtractionHook`（afterRun，order=100，调 extractAsync）与 `MemoryInjectionHook`（beforeRun，order=50，把 facts 前置进 run 首条消息）；
  - Love preparation 链路：LoveApp/AiController 拼 prompt 时显式调 `MemoryService.factsFor`（该链路不经 ReAct 循环，不硬套 hook）。

## 4. 落地批次与依赖

| 批次 | 内容 | 依赖 | 风险 |
| --- | --- | --- | --- |
| A 工作区 | WorkspaceService + 目录模型 + ToolFactory/三工具收口 + files 端点 | 无 | 低（路径来源变更，行为兼容） |
| B 技能 | SkillRepository + front-matter + PromptBuilder 接入 + reload/catalog | 无 | 中（需兼容租户开关与内置回落） |
| C 记忆 | V7 表 + MemoryService + 3 个 hook + love preparation 接入 | A（事实文件可存 workspace，弱依赖） | 高（LLM 提取质量/成本、压缩正确性） |

建议顺序 A → B → C，每批独立可回滚（A 不改行为、B 有内置回落、C 开关默认关）。

## 5. 测试与验收

### 单元测试

- 工作区：路径穿越/符号链接拒绝、listFiles 输出、三工具在新目录正常读写；
- 技能：front-matter 解析（含数组）、缺字段拒绝并跳过、reload 生效、无目录回落内置、租户开关叠加；
- 记忆：超预算触发压缩且保留最近 K 条、LLM 失败降级路径、事实 JSON 解析容错、upsert 去重、注入格式。

### 验收指标

- 新增技能 = 放一个 .md 文件 + POST /skills/reload，零代码改动；
- 模拟长 run：上下文字符数始终 < 预算，最近 6 条消息始终原样保留；
- 开启事实提取后，跨会话提问可见此前偏好事实注入 prompt（trace/detail 可查）。

## 6. 推荐落地顺序

1. 批次 A：工作区（一次会话可完成，含单测与端到端验证）；
2. 批次 B：技能仓库（含内置技能迁移为 markdown）；
3. 批次 C：记忆（先压缩 hook，再事实日志，最后提取/注入接 love 链路）。

不要在批次 C 之前开启任何 LLM 提取开关；不要让技能解析失败阻断启动（全部跳过并告警）。
