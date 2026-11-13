# 会话摘要：知识库文档清洗（规则层 P1）（2026-11-13）

> 后续若实现 LLM 清洗（P2），以本文件为基础追加，不要另建同主题摘要。

## 需求与范围

在「解析结果 → 切片 → embedding」之间补一层生产级文档清洗。用户计划书覆盖 4 个阶段，
本次只落地 **P1：规则清洗 + 清洗记录 + 基础校验 + 单测**；LLM 清洗按评审结论延后
（DashScope 欠费 + 千级块成本不可控），但数据结构与配置位已预留。

明确不做：不改解析服务、不改切片算法、不改检索与向量存储、不引入新依赖、不动前端。

## 设计要点

1. **两个关卡，覆盖所有 provider**：
   - 块级：`AgentKnowledgeDocumentService.structuredChunks()` 内 `mineruBlockParser.parse()` 之后、
     持久化与切片之前，`KnowledgeDocumentCleaner` 处理 `MineruBlock`；
   - 切片级：`indexDocument()` 中 `resolvedChunks` → `ChunkQualityGate`，是所有 provider
     （MinerU / 统一解析服务 / Docling 直切 / 本地兜底）的唯一汇聚点，避免 docx/txt 绕过清洗。
2. **只清洗 TEXT 块**：TABLE 保留结构化结果与 HTML，EQUATION 原样保留，FIGURE 保留引用与图注；
   文本块规范化不改写语义（不做 NFKC、不替换中文标点、不做同义改写）。
3. **安全边界**：
   - 噪声/页眉页脚删除率超过 `max-deletion-ratio`（默认 0.15）整体回滚为原始块；
     回滚只对 ≥200 字符的文档生效（短文档按比例判会放大误判）；
   - 完全重复块去除不计入回滚比例（不减少信息量），且只对 ≥40 字符的非标题文本生效；
   - 乱码只打 `GARBLED_REVIEW` 标记，不删除。
4. **页眉页脚识别**：跨页重复 + 短文本（≤60 字）+ 非标题；≥3 页才开始判定；
   指纹只剥离明确的尾部页码（`第 N 页`、`N/M`、`Page N`、`- N -`），
   **不把正文数字统一替换成占位符**——那会把「正文内容第 N 段」误杀成页眉（已修正）。
5. **段落合并**：仅同页相邻文本块；上一块不以句末标点结尾、下一块不以列表/章节编号开头；
   合并保留前一块 blockId / pageNumber，bbox 取并集；跨页禁止（保护引用回图的坐标定位）。
6. **可追溯**：`agent_knowledge_block.cleaned_text / cleaning_json`（version/actions/dropped/reviewRequired）
   与 `agent_knowledge_document.cleaning_stats`（块级 + 切片级字符数、丢弃原因、是否回滚）。

## 新增 / 修改文件

新增（`src/main/java/com/zwx/zwxagent/knowledge/cleaning/`）：

- `CleaningSettings`（app.knowledge.cleaning.*，可关闭）
- `TextNormalizer`（NFC、零宽/控制字符、连字符断词、汉字间空格、全角字母数字；切片级另去
  Markdown 图片语法与残留 HTML，含 `<table>` 时跳过）
- `NoiseDetector`（页码/纯符号/单字符重复；乱码判定）
- `FurnitureDetector`、`ParagraphMerger`
- `KnowledgeDocumentCleaner`（三遍：规范化+剔除 → 同页合并 → 完全重复去除；比例回滚）
- `ChunkQualityGate`（最小字符数、噪声、整片去重；表格行/图注/图纸问题豁免）
- 模型：`CleaningAction`、`CleanedBlock`、`BlockCleaningStats`、`BlockCleaningResult`、
  `ChunkGateStats`、`ChunkGateResult`

修改：

- `AgentKnowledgeDocumentService`：注入 cleaner/gate（字段注入，保持既有测试构造不变）；
  `structuredChunks` 接入清洗并落库清洗记录；`indexDocument` 接门禁并把统计写入 READY；
  `persistBlocks` 增加 `cleaned_text/cleaning_json` 列。
- `application.yml`：新增 `app.knowledge.cleaning` 配置块（enabled / min-chunk-chars /
  max-deletion-ratio / furniture-* / dedupe-* / max-paragraph-merge-chars）。
- `V50__knowledge_cleaning.sql`：块表加两列、文档表加 `cleaning_stats`。
- `docs/ROADMAP.md`：补充清洗层已完成项与 LLM 清洗待办。

## 验证

- 新增单测 22 例全绿：`TextNormalizerTest`(6)、`KnowledgeDocumentCleanerTest`(9)、
  `ChunkQualityGateTest`(7)。覆盖页码/符号噪声、跨页页眉、重复标题保护、同页断句合并、
  列表不误合、表格/公式不动、重复段去除、比例回滚、乱码标记、开关关闭、表格行/图注豁免、
  元数据与 id 保留、chunk 文本规范化。
- 既有知识库相关测试 28 例全绿（含 StructuredKnowledgeChunker / MineruBlockParser /
  DoclingServeChunkingService / FigureEnrichment / DocumentService 上传与向量批处理），无涟漪。
- `mvn -DskipTests package` 通过；本地后端重启，Flyway 成功应用 `V50 - knowledge cleaning`
  （20:50:11），`/api/health` 返回 ok。

## 遗留 / 后续

- **P2 LLM 清洗未做**：异常检测（OCR 疑似错误、跨页断句、标题层级异常）→ 结构化修改建议 →
  JSON Schema 校验 → 数字/日期/编号/否定词一致性校验 → 失败回退。默认关闭、按文档预算限流；
  复用 `ModelCatalogService.resolve(userId, "chat")` + `DynamicChatModelFactory`
  （`FigureEnrichmentService` 是现成模板）。DashScope 恢复后再启用。
- **未跑真实语料对照**：生产库现有 3 篇《新能源发电技术》文档（1620 切片）未重新索引。
  验证方式：复制文档重新上传或 `reindex-all` 后对比 `cleaning_stats` 与 golden set
  Recall@k/MRR；建议先备份 Weaviate 集合。
- **公式块保护依赖 type**：`StructuredKnowledgeChunker` 对 EQUATION 无独立分支，会按文本聚合；
  清洗层不改公式，但切片层仍可能把公式与正文合并。若要求公式独立成块，需另开一期改切片器。
- **视觉增强顺序**：清洗在图纸增强之前，figure 块本身不参与合并/丢弃（实例原样传递），
  分组逻辑不受影响；若未来允许丢弃 figure 块，需要复核 `FigureSupport.groupOf` 的 blockId 映射。

## 环境注意事项

- 清洗默认开启；回退方式：`APP_KNOWLEDGE_CLEANING=false` 后重启（无需回滚数据库，列留空即可）。
- `V50` 已在本地（指向服务器 PG 的隧道库）应用；**服务器部署时 Flyway 会自动补应用**，
  属于 additive DDL，不需要单独迁移脚本。
- 生产部署后老文档不会自动重洗：只有新上传或 `reindex-all` 的文档才会有清洗记录与统计。
