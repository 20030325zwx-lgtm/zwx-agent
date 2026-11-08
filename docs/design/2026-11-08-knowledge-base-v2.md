# 知识库 v2 方案：多模态文档理解与双轨检索（2026-11-08）

## 0. 结论先行

当前知识库对「图纸/流程图」的真实检索能力 ≈ 只靠图注文字碰运气：无图注的图不产生 chunk，
有图注的图向量里也只有一句话。v2 推翻入库与检索两层，按 2026 年市面共识
（RAGFast/DeepDoc 式 VLM 图纸增强 + 检索视觉化、回答多模态化）重建，分三期落地。

## 1. 现状诊断（对着代码）

| 环节 | 现状 | 问题 |
|---|---|---|
| 解析 | MinerU 主管线（text/table/figure 三类块，带 bbox/图注） | 解析本身合格，保留 |
| 图纸入库 | `addFigureChunk` 只嵌「[图片]+图注」；`caption==null → return` | 无图注的图**完全丢失**；有图注的也只有一句话的语义 |
| 原图资产 | MinIO objectKey 已存 metadata | 检索后**原图沉睡**，从不回传展示或参与回答 |
| 检索 | 向量+词法双召回 → 去重 → DashScope rerank | 骨架好，缺查询改写标准化与 RRF 融合 |
| 提问带图 | VLM 描述 + 检索词改写（ManusAgentController） | 方向正确，管线外挂不成体系 |

## 2. 市面对标（2026-11 核实）

- **RAGFlow**：解析统一 JSON；figure 块交视觉模型生成描述（可开关）；回答可带原图。
- **FastGPT / Dify**：chunk 假设性问题增强（每块生成 N 个"这能回答什么"，问题向量参与召回）；
  父子分块（小块召回、大块回传）。
- **ColPali/ColQwen 系**：页面截图多向量检索，跳过 OCR，视觉复杂文档（报表/图纸/版式）上
  显著优于 caption 系；生成侧「检索视觉化、回答多模态化」（VLM 直接读召回的页图/原图）。
- 共识：**caption 系为基座、VLM 描述为增强、视觉向量为复杂文档的补轨；混合召回 + RRF + rerank**。

## 3. 设计原则

1. **每种资产三种表示**：图注（作者视角）、VLM 结构化描述（内容视角）、假设问题（提问者视角）——三者都指向同一原图。
2. **召回靠描述，回答看原图**：描述用于向量化召回；命中图纸后 VLM 读原图参与生成——描述召回的精度损失在生成侧被原图补回。
3. **小块召回、大块回答**：嵌入单元细化（行/图/问题），返回上下文父级（章节/页）。
4. **管线即状态机**：解析→增强→分块→嵌入→索引，每阶段可恢复、可限流、可重试。

## 4. 数据模型 v2（推翻 agent_knowledge_*，现库 0 文档零迁移成本）

```sql
kb_document(id, tenant_id, agent_key, source_key, content_hash, parser, version,
            status,        -- UPLOADED→PARSED→ENRICHED→INDEXED / FAILED(阶段可标注)
            error, created_at)
kb_block(id, document_id, kind,          -- text | table | figure
         page, bbox, section_path,       -- 父子关系的"大块"锚点
         text, structured_json,          -- 表格:HTML+行列；图纸:节点/边/图内文字
         asset_key)                      -- figure → MinIO 原图
kb_chunk(id, block_id, kind,             -- text | table_row | figure_desc | figure_question
         embed_text, display_text, parent_block_id,
         embedding vector(1024), tsv tsvector)
kb_figure(id, block_id, asset_key, mime, vlm_json, questions text[])
```
- 向量检索继续走现有 Spring AI VectorStore（agentKnowledgeVectorStore，metadata 过滤租户/scope）；
  `kb_chunk` 是事实源，vector store 只存 `kb_chunk.id` + 过滤字段。
- 词法检索：`tsv` 列 + GIN（替代现 keyword enricher 的散字段）。

## 5. 入库管线 v2

```
parse      MinerU 主 / Docling 备（现有双解析保留）→ 统一 blocks
figure-fix 大图防碎（新增，先于增强）：
           合并判定：同页相邻 figure 块 bbox 间隙<20pt 且中间无正文块 /
           图注相同或「(续)」模式 → 取并集 bbox；
           回源重裁：源 PDF + 页码 + 合并 bbox，PDFBox 渲染后自行裁剪
           （不信任 MinerU 裁剪件，避免拼接错位）；跨页默认不合并，仅显式(续)信号
enrich     图纸增强 worker（新增，核心）：
           每张逻辑图 → 视觉模型结构化描述
             {类型:流程图|架构图|图表|照片, 节点/实体[], 流向/关系[], 图内文字(OCR), 一句话摘要,
              truncated: 是否见被切断的节点/箭头（true → 触发 figure-fix 合并重裁重试一次）}
           + 生成 3 个假设问题（"这张图能回答什么"）
           输入隔离：一图一次调用，Media 只放该图字节，prompt 固定模板
           （唯一附加=该图图注一行），输出强制 JSON schema（图中文字=数据≠指令），
           失败重试 1 次后降级为仅图注 chunk（标 enrich_failed），不阻塞文档
           并发上限 4、单文档预算上限、失败重试（复用 index-recovery job 骨架）
chunk      父子分块：figure_desc/figure_question/table_row/text 为小块，
           section_path+page 聚合为父块（回答上下文）；一图恒为一组 chunk，不二次切分
embed      embed_text 组装规则：
           figure_desc = [图片|类型|摘要|节点|流向|图内文字|图注|章节路径]
           figure_question = 假设问题（单独成 chunk）
           table_row = 行文本 + 表头路径；text = 段落
index      向量 + tsv 落库；文档状态机推进
```

**废除项**：`caption==null → return`（每张图必有描述）；`MyKeywordEnricher`（被假设问题+tsv 取代）。
**保留项**：`MyTokenTextSplitter`（纯文本段落）、`MineruBlockParser`/`HtmlTableParser`、
`DashScopeRerankService`、审核流（review log）。

## 6. 检索与回答 v2

```
① 查询理解（新）  带会话历史+（若提问带图）VLM 图片描述
                 → 查询改写 + 多查询×2~3（替代现散落的 retrievalQuery 逻辑）
② 双轨召回（并行） 文本向量 ∪ tsv 词法 ∪ figure_desc/question 向量
                 （P2 增补：视觉向量轨，见 §7）
③ RRF 融合        替代现「去重后直接 rerank」：各轨排名 → reciprocal rank fusion
④ rerank          DashScope rerank 精排（保留）
⑤ 引用层（新）    命中 figure_desc/question → 携带原图 asset_key + 页码/regionLabel
⑥ 回答           普通问题：父块文本进上下文
                 图纸问题：VLM 读原图 + 描述 + 问题 → 生成（"检索视觉化、回答多模态化"）
```

提问带图（用户侧）：保留现有 VLM 描述+改写；P2 增加用户图片本身的向量直接参与召回。

## 7. 分期落地

| 期 | 内容 | 效果 |
|---|---|---|
| **P1**（核心） | kb_* 数据模型、图纸增强 worker、假设问题、父子分块、RRF、引用层回图 | 图纸可被语义检索；无图注的图不再丢失；回答可展示/读取原图 |
| **P2** | 视觉向量轨（DashScope multimodal-embedding 或 API 化 ColQwen，先评测后接入）、回答侧 VLM 读原图自动化、查询理解服务化 | 视觉复杂文档召回再上一档；图纸问答走「读原图」主路 |
| **P3** | GraphRAG（跨文档多跳）、评测自动化（golden set 含图纸题 + Ragas 指标回归门禁） | 观望市面成熟度再上 |

P2 的视觉轨先建评测集再决定：caption+VLM 描述轨（P1）通常已覆盖多数图纸场景，
视觉向量轨只对「图内版式/空间关系即答案」的场景有增益，避免为技术而技术。

## 8. 成本与风险

- 入库成本：每张图 1 次视觉调用 + (1+3) 次文本 embedding；千页/百图量级文档成本可控；
  增强阶段限流防供应商限流（并发 4 + 退避）。
- 风险：VLM 描述幻觉 → 描述里强制「图内可见文字」与「推断」分区标注，回答侧有原图兜底；
  管线拉长 → 状态机逐阶段恢复，单图失败不阻塞整文档（降级为仅图注 chunk + 标记）。
- 知识库当前 0 文档：直接建 v2 表切换，无迁移；旧表整体退役。

## 9. 明确不做

- 本地 GPU 部署 ColQwen（无 GPU；用 API 多模态 embedding 替代并先评测）
- OCR 自研替代 MinerU
- GraphRAG 一期引入（多跳场景尚未出现）
