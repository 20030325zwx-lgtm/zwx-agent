# 知识库文档版本与有效性治理方案

> 状态：第一批（最小闭环 + 前端展示）已实施，见 `context/session-2026-09-07-knowledge-versioning.md`；hash 去重、publish/rollback/versions/retire API、冲突检测尚未实施
>
> 目标：在同名文档、重复文档、新旧版本并存或规则冲突时，保证检索只使用当前有效内容，并让回答能够解释其来源。

## 1. 背景与现状

当前智能体私有知识库由 `agent_knowledge_document` 保存文档记录，由 `agent_knowledge_vector` 保存切片和向量。上传逻辑位于 `AgentKnowledgeDocumentService`：

- 以 `tenant_id + agent_key + filename` 判断同名文档；
- 上传前删除同名旧记录、对象和向量；
- 新文档异步切片并写入向量表；
- 检索按 `tenantId + agentKey` 过滤，再执行向量召回和可选 rerank。

这种方式可以避免同名新旧版本同时参与检索，但有三个问题：

1. 旧版本不可追溯，无法审计或回滚；
2. 改名、复制文件、不同格式上传会绕过同名判断，导致重复内容并存；
3. rerank 只判断语义相关性，不知道哪个版本生效，不能单独解决新旧文档冲突。

本方案把“哪些内容有资格被检索”和“候选内容如何排序”分开：先做版本和有效性治理，再做向量召回与 rerank。

## 2. 设计目标与非目标

### 2.1 目标

- 同一租户、同一智能体、同一逻辑文档只允许一个版本处于 `ACTIVE`；
- 新版本先完成解析和索引，验证成功后再切换为当前版本，避免半成品覆盖线上版本；
- 历史版本保留，支持查看、回滚和审计；
- 检索默认只查询当前有效版本；
- 引用和 RAG trace 显示文档版本、生效时间和来源；
- 对多个有效版本或同一逻辑文档的内容冲突进行检测并可观测；
- 与现有同名替换行为兼容，逐步迁移而不是一次性破坏数据。

### 2.2 非目标

- 不让生成模型自行决定哪个版本有效；
- 不在第一阶段实现全自动事实裁决；
- 不把历史版本全部送入模型后依赖 Prompt 解决冲突；
- 不改变 Love 内置知识库的现有发布方式，除非后续单独迁移。

## 3. 核心概念

### 3.1 逻辑文档

`logical_key` 表示同一份业务资料的稳定身份，不等于文件名。例如：

```text
refund-policy
travel/refund-policy
employee-handbook/leave-policy
```

文件改名、格式从 Markdown 改为 PDF，只要 `logical_key` 不变，仍属于同一份文档的不同版本。

### 3.2 版本

同一 `tenant_id + agent_key + logical_key` 下的文档按整数递增：`v1`、`v2`、`v3`。版本号由服务端生成，不信任客户端传入的版本号。

### 3.3 生命周期状态

建议使用以下状态：

| 状态 | 是否参与检索 | 含义 |
| --- | --- | --- |
| `UPLOADING` | 否 | 文件和元数据尚未完整落库 |
| `INDEXING` | 否 | 正在解析、切片、写向量 |
| `READY` | 否 | 索引完成，但尚未发布 |
| `ACTIVE` | 是 | 当前生效版本 |
| `ARCHIVED` | 否 | 历史版本，保留用于审计和回滚 |
| `FAILED` | 否 | 解析或索引失败 |
| `CONFLICT` | 否 | 检测到未解决的版本或内容冲突 |

`ACTIVE` 是检索资格，不应由“最新上传时间”隐式推断。

## 4. 数据模型调整

建议新增 Flyway migration，例如 `V6__add_knowledge_document_versioning.sql`。

### 4.1 `agent_knowledge_document` 新增字段

```sql
ALTER TABLE agent_knowledge_document
    ADD COLUMN logical_key VARCHAR(255),
    ADD COLUMN version_no INTEGER,
    ADD COLUMN lifecycle_status VARCHAR(16),
    ADD COLUMN content_sha256 CHAR(64),
    ADD COLUMN effective_from TIMESTAMPTZ,
    ADD COLUMN effective_to TIMESTAMPTZ,
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN supersedes_document_id VARCHAR(64),
    ADD COLUMN created_by VARCHAR(64),
    ADD COLUMN conflict_reason TEXT;
```

说明：

- 保留现有 `status` 作为索引任务状态，或在实施时统一重命名；不应让“索引完成”和“已发布”共用一个状态字段；
- `lifecycle_status` 管理发布生命周期；
- `content_sha256` 用于检测完全重复内容；
- `effective_from/effective_to` 用于定时生效和历史查询；
- `supersedes_document_id` 记录版本链；
- `created_by` 用于审计。

### 4.2 约束与索引

```sql
CREATE UNIQUE INDEX uk_agent_knowledge_logical_version
    ON agent_knowledge_document (tenant_id, agent_key, logical_key, version_no);

CREATE UNIQUE INDEX uk_agent_knowledge_active_version
    ON agent_knowledge_document (tenant_id, agent_key, logical_key)
    WHERE lifecycle_status = 'ACTIVE';

CREATE INDEX idx_agent_knowledge_retrieval_scope
    ON agent_knowledge_document (tenant_id, agent_key, lifecycle_status, effective_from, effective_to);

CREATE INDEX idx_agent_knowledge_content_hash
    ON agent_knowledge_document (tenant_id, agent_key, content_sha256);
```

PostgreSQL 的部分唯一索引保证同一逻辑文档最多只有一个 `ACTIVE` 版本。发布动作必须在事务内完成，不能只依赖应用层检查。

### 4.3 向量元数据

写入 `agent_knowledge_vector` 时，为每个切片增加：

```text
documentId
tenantId
agentKey
logicalKey
versionNo
lifecycleStatus
contentSha256
chunkIndex
```

检索过滤优先使用切片元数据中的 `lifecycleStatus = ACTIVE`，并同时在文档表中校验当前版本，防止发布切换期间出现脏向量。

## 5. 上传、索引与发布流程

### 5.1 默认上传流程

```text
上传文件
  ↓
计算 SHA-256，解析 logical_key
  ↓
若相同 logical_key + hash 已存在 ACTIVE：返回 DUPLICATE，不新建版本
  ↓
创建新版本，lifecycle_status = INDEXING
  ↓
保存对象、解析、切片、写入向量
  ↓
索引成功：lifecycle_status = READY
  ↓
执行发布事务：旧 ACTIVE → ARCHIVED，新 READY → ACTIVE
  ↓
旧版本向量标记 ARCHIVED 或从检索过滤中排除
```

### 5.2 原子发布

发布事务必须包含：

1. 锁定同一 `tenant_id + agent_key + logical_key` 的版本记录；
2. 确认目标版本状态为 `READY`；
3. 将现有 `ACTIVE` 版本改为 `ARCHIVED`，填写 `archived_at` 和 `effective_to`；
4. 将目标版本改为 `ACTIVE`，填写 `published_at` 和 `effective_from`；
5. 提交事务后，异步刷新或删除旧版本向量。

旧向量清理失败不能回滚已完成的发布；检索必须依靠元数据过滤，保证旧向量暂时残留也不会被使用。

### 5.3 失败处理

- 文件上传失败：不创建可检索版本；
- 解析失败或空文档：版本变为 `FAILED`，现有 `ACTIVE` 保持不变；
- 向量写入失败：删除本批次已写入的切片，版本变为 `FAILED`；
- 发布失败：事务回滚，旧 `ACTIVE` 保持不变；
- 新版本未发布前，线上回答继续使用旧 `ACTIVE` 版本。

这保证了“新版本不完整时不影响当前服务”。

## 6. 重复文档与逻辑文档识别

### 6.1 完全重复

使用规范化文本的 SHA-256：

- 统一换行和空白；
- 去除解析器无意义的元信息；
- 对文本进行 UTF-8 编码后计算 hash。

同一作用域内相同 `content_sha256` 的文档不重复发布。默认返回已有文档 ID，并在审计中记录一次重复上传。

### 6.2 同名但内容不同

默认把同名文件映射到同一个 `logical_key`，生成新版本，而不是删除旧记录。

### 6.3 改名或不同格式

不能只靠文件名识别。提供三种方式，按可靠性排序：

1. 管理员上传时选择已有逻辑文档；
2. 文件中声明稳定 ID，例如 front matter 的 `document_key`；
3. 文件名规范化作为兜底，但不能保证准确识别。

自动根据文本相似度猜测两个文件是否同一逻辑文档，只用于提示，不自动合并或覆盖。

## 7. 检索与 rerank 顺序

推荐顺序如下：

```text
1. 作用域过滤：tenantId + agentKey
2. 有效性过滤：lifecycle_status = ACTIVE
3. 时间过滤：effective_from <= now < effective_to
4. 向量召回候选
5. 按 logical_key 去重，确保一个逻辑文档只保留当前版本
6. rerank 精排
7. 版本/来源一致性校验
8. 取最终 topK 注入模型上下文
```

关键点：版本过滤必须发生在 rerank 之前。不能把新旧版本都发送给 rerank，然后期待模型自动选新版本。

最终排序建议为：

```text
有效版本资格 > 逻辑文档去重 > rerank 分数 > 向量相似度
```

`rerank` 只在同一批有效候选中比较相关性，不负责版本治理。

## 8. 冲突检测与回答策略

### 8.1 数据层冲突

以下情况直接阻止发布或标记 `CONFLICT`：

- 同一逻辑文档出现两个 `ACTIVE` 版本；
- 一个版本的 `effective_from/effective_to` 与另一个版本重叠；
- 发布目标索引状态不是 `READY`。

数据库唯一索引处理第一类，事务校验处理第二、三类。

### 8.2 内容层冲突

第一阶段不做复杂的自然语言事实推理，只做可解释检测：

- 同一逻辑文档只允许一个 ACTIVE 版本；
- 同一问题召回的多个片段，如果来自不同逻辑文档且来源声明冲突，记录 `conflict_detected`；
- 对高风险字段（价格、期限、资格、联系方式、政策条款）可配置关键词规则，命中冲突时降低自动回答置信度。

当存在未解决冲突时，Prompt 要求模型：

```text
优先使用当前有效版本；不得使用 ARCHIVED/FAILED 文档作为当前规则。
若多个当前有效来源互相矛盾，明确说明知识库存在冲突，并列出来源，不要自行猜测。
涉及期限、价格、资格和政策时必须带来源和版本。
```

如果冲突等级达到阻断阈值，服务端不应生成确定性结论，而应返回“需要管理员确认”的结果。

## 9. API 与管理界面建议

第一阶段建议增加以下接口：

```text
POST /api/agent-knowledge/documents
  上传并创建新版本

GET /api/agent-knowledge/documents
  默认返回当前版本，可通过 includeArchived=true 查看历史

POST /api/agent-knowledge/documents/{id}/publish
  发布 READY 版本

POST /api/agent-knowledge/documents/{id}/rollback
  将指定历史版本复制为新的发布版本，避免直接修改历史记录

GET /api/agent-knowledge/documents/{id}/versions
  查看逻辑文档版本链

POST /api/agent-knowledge/documents/{id}/retire
  将当前版本下线，不自动发布其他版本
```

管理页面需要显示：逻辑文档名、版本号、状态、上传者、索引状态、发布时间、生效时间、内容 hash、替代关系和冲突提示。

上传后的默认策略建议为“自动索引、人工发布”。低风险租户可以配置为“索引成功自动发布”。

## 10. 迁移计划

### 阶段 0：数据盘点

- 为现有记录生成 `logical_key = normalized(filename)`；
- 将 `READY` 且最新的同名记录标记为 `ACTIVE`；
- 旧记录若已被当前代码删除，则无法恢复历史版本；
- 计算已有文档的内容 hash；
- 检查重复、空文档和失败索引。

### 阶段 1：后端兼容字段

- 增加 migration 和实体字段；
- 保留当前上传接口；
- 旧接口上传仍执行“创建新版本并自动发布”；
- 检索开始过滤 `ACTIVE`，没有字段的历史数据按迁移脚本补齐。

### 阶段 2：原子发布与历史版本

- 上传改为 `INDEXING → READY`；
- 增加 publish、versions、rollback 接口；
- 旧向量保留但不参与检索；
- 管理页面加入版本列表和发布操作。

### 阶段 3：重复和冲突治理

- 完全重复检测；
- 逻辑文档选择器；
- 冲突检测和 RAG trace 字段；
- 评测集覆盖新旧版本、回滚、重复上传和冲突回答。

## 11. 测试与验收标准

### 单元测试

- 同一 `logical_key` 连续上传生成 v1、v2；
- v2 索引失败时 v1 仍为 ACTIVE；
- 发布 v2 后 v1 自动 ARCHIVED；
- 并发发布不会出现两个 ACTIVE；
- 相同 hash 不创建重复版本；
- 改名但手工指定同一 logical_key 能进入同一版本链；
- 检索不会返回 ARCHIVED、FAILED、CONFLICT 文档；
- rerank 失败时仍只在 ACTIVE 候选中降级排序。

### 集成测试

- 版本切换期间旧回答仍可用；
- 新版本发布后新请求只看到新版本；
- 回滚后引用和 trace 显示回滚后的版本；
- 旧向量延迟清理期间不会污染结果；
- 租户和智能体隔离不被版本查询破坏。

### 验收指标

- 任一逻辑文档最多一个 ACTIVE 版本；
- FAILED/ARCHIVED 文档检索命中数为 0；
- 新版本索引失败不影响旧版本回答；
- RAG trace 能回答“使用了哪个文档、哪个版本、何时生效”；
- 发生冲突时不输出未经标注的确定性新旧规则。

## 12. 推荐落地顺序

优先实现以下最小闭环：

1. `logical_key + version_no + lifecycle_status`；
2. 部分唯一索引保证单一 ACTIVE；
3. `INDEXING → READY → ACTIVE` 原子发布；
4. 检索只查询 ACTIVE；
5. 引用和 trace 增加版本信息；
6. 再加入 hash 去重、回滚和冲突检测。

不要先改 Prompt 或调大 rerank 权重。只要旧版本仍有资格进入检索池，模型就仍可能得到互相矛盾的上下文。
