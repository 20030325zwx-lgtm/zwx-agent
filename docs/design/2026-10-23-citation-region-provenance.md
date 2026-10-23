# 回答引用定位到具体内容区域（2026-10-23）

> 状态：批次 1（来源块 + 表格引用）已实施（2026-10-23）；批次 2–4 待做。目标不是重做切片，而是让每个切片保留“可回看的来源区域”，引用时能定位到具体段落、表格行或图片。

## 1. 目标

用户点击回答里的引用时，看到的应该是：

- 文本：命中的具体段落/条款，带页码和章节路径；
- 表格：渲染后的真实表格，并高亮命中的行/单元格，而不是原始 HTML；
- 流程图/图片：原图或区域裁剪，并展示图注、图内文字和视觉描述。

切片怎么切、切多大是检索实现细节；**引用预览必须基于来源区域，而不是基于切片文本。**

## 2. 当前问题

1. `AgentKnowledgeDocumentService.mineruChunks()` 只消费 `full.md`，`MineruParseResult` 里的 JSON、HTML、图片没有进入索引链路。
2. 引用预览接口只返回 `AgentKnowledgeChunkPreview(content)`；前端用 `<pre>` 直接展示切片文本。
3. 旧切片或部分解析链路会把 `<table>...</table>` 原样写进切片，预览里出现大量 HTML 标签。
4. 一个切片可能包含整张表或多段内容；引用只能定位到“切片 #14”，无法告诉用户是表格的哪几行。
5. 图片/流程图只以图片链接或图注进入文本，引用无法展示图片本身，也无法说明流程图内容。
6. 重新索引会删除旧切片；如果引用只保存切片 ID，旧回答在重建索引后可能无法预览。

## 3. 核心原则

- **检索文本与展示区域分离**：`content` 用于 embedding；`region` 用于引用预览。
- **块级身份稳定**：`documentId + blockId + rowStart/rowEnd` 是引用定位键，不依赖切片 ID。
- **表格不展示原始 HTML**：入库时解析为 header/rows；前端按结构化数据渲染。
- **图片必须有语义**：图注 + 图内文字 + 视觉描述至少有一项进入检索文本和引用详情。
- **旧数据可降级**：没有 region 的旧切片仍可显示纯文本，并提示“旧索引，建议重建”。

## 3.1 端到端处理流程

```text
MinerU 结果 ZIP
  ├─ full.md                  （文本兜底）
  └─ content_list.json
        ↓
MineruBlockParser
  ├─ TEXT     → 章节聚合文本块
  ├─ TABLE    → HtmlTableParser → headers + rows
  ├─ FIGURE   → 图注/图片路径
  └─ EQUATION → 公式文本
        ↓
agent_knowledge_block（完整来源块）
        ↓
StructuredKnowledgeChunker
  ├─ 文本：按章节/长度聚合成切片
  ├─ 表格：每行一条，重复表头和表名
  └─ metadata：blockId / page / rowStart / rowEnd / regionLabel
        ↓
PGVector 切片（检索用）
        ↓
RAG 召回 → RagTraceCandidate（带区域引用）
        ↓
回答引用 [文件名#切片]
        ↓
GET /agent-knowledge/chunks/{chunkId}
  ├─ 命中切片 → 读取 metadata + 来源块
  └─ 切片已重建 → 用 documentId + blockId 回退
        ↓
CitationPreview：文本 / 表格高亮行 / 图片区域
```

## 4. 数据模型

### 4.1 新增来源块表

新增迁移 `V19__add_knowledge_source_block.sql`：

```sql
CREATE TABLE IF NOT EXISTS agent_knowledge_block (
    id                  VARCHAR(64) PRIMARY KEY,
    document_id         VARCHAR(64) NOT NULL,
    tenant_id           VARCHAR(64) NOT NULL,
    agent_key           VARCHAR(32) NOT NULL,
    block_id            VARCHAR(128) NOT NULL,
    source_type         VARCHAR(16) NOT NULL, -- TEXT / TABLE / FIGURE / EQUATION
    page_number         INTEGER,
    bbox                JSONB,
    caption             TEXT,
    text_content        TEXT,
    html_content        TEXT,
    json_content        JSONB,
    image_object_key    VARCHAR(512),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, block_id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_block_scope
    ON agent_knowledge_block (tenant_id, agent_key, document_id, source_type);
```

- `document_id` 对应版本化后的文档记录，旧版本块保留，引用可回看。
- 表格的完整 header/rows 存在 `json_content`，原始 HTML 仅作为辅助，不进入回答文本。
- 图片存在 OSS/工作区，`image_object_key` 指向原图；`bbox` 记录图块在页面中的位置。

### 4.2 切片 metadata 扩展

每个向量切片增加：

```json
{
  "blockId": "table-2.3.2-1",
  "sourceType": "TABLE",
  "pageNumber": 12,
  "sectionPath": "集成测试报告 > 2.3.2 待办功能流程测试",
  "rowStart": 3,
  "rowEnd": 6,
  "regionLabel": "表 2.3.2-1 · 第 12 页 · 第 4 行",
  "chunkSchemaVersion": 2
}
```

文本块可只带 `blockId/sourceType/pageNumber/bbox`；图片块带 `blockId/figureCaption`。

### 4.3 引用轨迹扩展

`RagTraceCandidate` 增加（保留旧字段兼容）：

- `chunkId`（现有 `documentId` 字段实际上是切片 ID，新增明确字段）
- `sourceDocumentId`
- `blockId`
- `sourceType`
- `rowStart` / `rowEnd`
- `regionLabel`

这样回答落库时，即使切片后来被重建，仍能通过 `blockId` 找到来源块。

## 5. 解析与切片链路

### 5.1 解析结果持久化

- MinerU 完整结果包落 OSS：`knowledge/{tenant}/{agentKey}/{documentId}/mineru/`。
- 保存 manifest：文件清单、hash、parser/backend、解析时间。
- `content_list.json` 解析为 block 列表，写入 `agent_knowledge_block`。
- Markdown 仍保留，作为文本块和兜底来源。

### 5.2 表格

- 优先消费 `content_list.json` 的 `table` 块，读取 `table_body` HTML。
- 复用现有 rowspan/colspan 展开逻辑，抽成独立 `HtmlTableParser`。
- 表格模型：`caption + headerRows + rows + cellRefs`。
- 推荐按行切片（每行一个 chunk）：
  - 文本用于 embedding：`[表格] 表名；列名：值；...`，每条重复表头和表名；
  - metadata 记录 `rowStart/rowEnd`，引用时只高亮命中的行；
  - 多行问题通过召回多个行切片解决；
  - 完整表格写入 `agent_knowledge_block.json_content`
- 跨页表按 caption + header 合并；找不到 caption 时按相邻页表头签名合并。
- Markdown 管道表也转成同样的行记录，不再只做长表切分。

### 5.3 图片/流程图

- 解析 `content_list.json` 的 `image/figure` 块，保存图片到 OSS。
- 视觉模型生成：
  - 图注；
  - 图内可读文字；
  - 流程图节点与连线；
  - 一句话摘要。
- 入向量库的文本：`[流程图] 标题（第 N 页）：摘要；节点：...；分支：...`
- `agent_knowledge_block` 存 `caption/text_content/json_content/image_object_key`。
- 限制：每篇最多处理 N 张图（建议 10）、并发 1–2、按图片 hash 缓存、超时回退到图注。

### 5.4 文本

- 用 `content_list.json` 的 text 块补 page/bbox/section。
- 现有 Markdown 切片逻辑保留；按 block 对齐 metadata。
- 找不到 block 时允许只有 page/section，引用仍可显示段落文本。

## 6. 接口

### 6.1 引用预览接口

`GET /api/ai/agent-knowledge/chunks/{chunkId}` 返回扩展结构，保持旧字段兼容：

```json
{
  "chunkId": "...",
  "documentId": "...",
  "filename": "2026综合管控平台集成对接验收规范标准.docx",
  "chunkIndex": 14,
  "pageNumber": 12,
  "sectionPath": "集成测试报告 > 2.3.2 待办功能流程测试",
  "sourceType": "TABLE",
  "regionLabel": "表 2.3.2-1 · 第 12 页 · 第 4 行",
  "displayText": "[表格] ...（已去除 HTML 标签）",
  "table": {
    "caption": "2.3.2 待办功能流程测试",
    "headers": ["测试版块", "测试动作", "测试对象", "检验点", "检查内容", "测试结果"],
    "rows": [["撤回流", "发起方", "...", "...", "...", ""]],
    "highlightRows": [3, 4, 5, 6]
  },
  "figure": {
    "caption": "报销审批流程",
    "description": "提交申请 → 主管审批 → 财务复核；金额大于 5000 时增加总经理审批",
    "imageUrl": "https://...",
    "bbox": [0.12, 0.33, 0.88, 0.71]
  }
}
```

- 优先按 `chunkId` 找切片；找不到时按 `documentId + blockId` 找 block，兼容重建索引后的旧引用。
- 旧切片没有 region 时，`displayText` 用 jsoup 去除 HTML 标签，并附 `legacy: true`。

### 6.2 原文件定位

- 文本/表格：返回 `pageNumber`、`bbox`。
- 前端“打开原文档”跳转到 `KnowledgeDocumentViewer`，支持页码定位；PDF 可后续增加区域高亮。

## 7. 前端改造

新增 `CitationPreview.vue`，替换 `ChatRoom` 里的 `<pre>`：

- 头部：文件名、页码、章节路径、`regionLabel`。
- `sourceType=TABLE`：渲染真实表格，表头 sticky，命中行高亮；提供“查看原页”。
- `sourceType=FIGURE`：展示图片、图注、视觉描述；有 bbox 时叠加高亮框。
- `sourceType=TEXT`：展示命中段落并高亮关键词/句子，显示页码与章节路径。
- 旧数据：显示纯文本，标注“旧索引”。

引用卡片本身也改为显示区域摘要，而不是截断的原始内容：

```text
2026综合管控平台...docx
表 2.3.2-1 · 第 12 页 · 第 4 行
撤回流程 / 发起方 / 检查未审批人的待办是否被正常删除…
```

## 8. 实施批次

### 批次 1：来源块与表格引用（优先）

1. V19 建表；`AgentKnowledgeBlock` 读写。
2. 持久化 MinerU 结果包与 manifest。
3. 解析 `content_list.json` 为 block；Markdown 兜底。
4. 抽出 `HtmlTableParser`，表格转 header/rows。
5. 表格行组切片，写 block 与 metadata。
6. 扩展引用预览接口，返回 table region。
7. 前端 `CitationPreview` 表格渲染 + 行高亮。
8. 清理旧 HTML 切片，提供重建索引入口。
9. 验收：引用卡片显示表名/页码/行号；弹窗渲染真实表格并高亮命中行；不再出现 `<table>` 标签。

### 批次 2：图片与流程图引用

1. 图片块落 OSS，记录 objectKey/bbox/caption。
2. 视觉模型生成图内文字、节点关系、摘要。
3. 图片切片 + block 落库。
4. 引用接口返回 figure region。
5. 前端图片预览 + 描述 + bbox 高亮。
6. 验收：流程图引用能显示原图、图注和流程描述；无图注时至少显示视觉摘要。

### 批次 3：文本精确区域与旧引用兼容

1. 文本块补 page/bbox/section。
2. 文本引用高亮命中段落。
3. 引用轨迹增加 `blockId/regionLabel`。
4. 按 `chunkId` → `documentId+blockId` 的回退查找。
5. 验收：重建索引后旧回答的引用仍能打开对应块。

### 批次 4：评测与回归

1. 20–30 条真实问题：表格字段、跨行条件、流程分支、普通文本。
2. 指标：引用区域命中率、答案正确率、无引用回答率、旧索引降级率。
3. 对复杂跨页表、合并单元格、扫描件表格、无图注流程图建立专项用例。
4. 更新 `docs/test/cases.md` 与测试结果。

## 9. 风险与对策

| 风险 | 对策 |
| --- | --- |
| MinerU JSON schema 因后端/模型变化 | 解析器做字段兜底；缺 JSON 时回退 Markdown |
| 表格 HTML 结构不合法 | jsoup 容错解析；失败时降级为纯文本行记录 |
| 视觉模型成本和延迟 | 限制图片数/并发、图片 hash 缓存、超时回退图注 |
| 重建索引导致旧引用失效 | 块表按版本保留；引用带 blockId；接口支持回退查找 |
| 表格行号与前端高亮错位 | 以解析后的 rows 数组为准，rowStart/rowEnd 使用同一索引 |
| 原始 HTML 注入 | 前端只渲染结构化 rows；必须渲染 HTML 时用 DOMPurify |

## 10. 推荐的第一步

先做批次 1 的“表格引用闭环”：

1. 持久化解析包 + block 表；
2. 表格从 `content_list.json` 转 header/rows；
3. 引用接口返回表格区域；
4. 前端弹窗渲染表格并高亮命中行；
5. 重建现有文档索引。

这一步完成后，用户点引用就能看到“表格的哪几行”，而不是一整块 HTML。图片/流程图和文本精确区域可以并行或随后推进。


## 11. 批次 1 已实施（2026-10-23）

- 新增 `V19__add_knowledge_source_block.sql` 与 `agent_knowledge_block` 表。
- 新增 `HtmlTableParser`、`MineruBlockParser`、`StructuredKnowledgeChunker`、`MineruParseResult` 结果包采集。
- MinerU 解析结果落 OSS：`knowledge/{tenant}/{agent}/{documentId}/mineru/`，并保存 manifest。
- 表格从 `content_list.json` 的 `table_body` 转 header/rows，按行切片；metadata 写入 `blockId/sourceType/pageNumber/rowStart/rowEnd/regionLabel`。
- 文本块按章节聚合，带 page/blockId；图片块先以图注进入索引，视觉理解为批次 2。
- `AgentKnowledgeChunkPreview` 增加 `displayText/pageNumber/sectionPath/sourceType/regionLabel/legacy/table`。
- 引用接口支持 `chunkId` 查不到时用 `documentId + blockId` 回退到来源块。
- `RagTraceCandidate` 增加 `chunkId/sourceDocumentId/blockId/sourceType/rowStart/rowEnd/regionLabel`，并在检索轨迹中对 HTML 摘要做纯文本化。
- 前端新增 `CitationPreview.vue`：表格渲染、命中行高亮、区域标签；旧切片显示纯文本并标注“旧索引”。
- 验证：后端 210 个测试通过（1 跳过）；前端构建通过；Playwright + mock API 验证表格引用弹窗高亮 3 行、无原始 `<table>` 标签。

### 使用说明

- 旧文档需要重新解析/重建索引后才会生成 `agent_knowledge_block` 和新 metadata；未重建的旧引用会以纯文本降级显示。
- Docling/本地解析路径暂未生成表格来源块，本批次优先覆盖 MinerU + `content_list.json`。
