# 会话摘要：知识库 P1 落地——图纸视觉增强 + 防碎合并 + 兄弟扩展（2026-11-08 深夜）

## 需求

用户确认知识库 v2 方案后要求「整改 + 更新服务」。本批为 v2 P1 核心：图纸可检索。

## 实现（knowledge 模块）

- `FigureDescription`：视觉增强结构化结果（type/summary/nodes/flows/visibleText/inferred/
  questions/truncated），宽松 JSON 解析（剥围栏），可见文字与推断强制分区。
- `FigureGrouper`：大图防碎合并——同页相邻 figure 块（一轴重叠 ≥50% 且另一轴间隔 <15pt）、
  图注相同/一方缺失/「(续)」信号（(续) 是唯一合法跨页合并），组上限 4。
- `FigureEnrichmentService`：一图组一次视觉调用（多碎片=多 Media 单调用），固定模板 +
  强制 JSON + 解析失败重试 1 次 + 单文档预算上限（默认 30）；输入隔离：只带该图裁剪件
  与一行图注 hint。**修复过一处真 bug：tryDescribe 误传 FIGURE_PROMPT 而非组装后的
  instruction，图注提示从未生效（测试捕获）。**
- `StructuredKnowledgeChunker`：figure 分支重写——有描述产出 figure_description chunk
  （图纸线性化文本：类型/摘要/组成要素/流向/图内文字/推断，图注缺失由描述兜底）+
  ≤4 个 figure_question chunk；无描述降级为仅图注；图注描述皆无才跳过。
  metadata 新增 chunkRole/sectionPath/figureImages/figureFragments/figureType/figureTruncated。
- `VisionAnalysisService.describeImages`：多图单调用入口（字节直传）。
- 检索侧父子分块（轻量）：`AgentKnowledgeRagService.expandSiblings`——命中文本/表格块后按
  documentId+sectionPath 拉同章节兄弟块进上下文（默认开：前 2 命中、每节 ≤4 条），
  图纸块不扩展。`app.agent.rag.sibling-expansion-*` 可关。
- 接线：`AgentKnowledgeDocumentService.mineruChunks` 装配（enrichment @Resource 字段注入
  避免破坏测试构造；身份用 agent_knowledge_document.created_by）。
- 配置：`app.knowledge.figure-enrichment-*`、`app.agent.rag.sibling-expansion-*`。

## 验证

- 定向 19/19：FigureGrouper 7 + FigureEnrichment 5 + Chunker 5（含 3 新用例）+ 兄弟扩展 2。
- 全量 645 例：失败/错误全部为既有基线（创作模块 FK + DashScope 欠费），无涟漪。
- 又见 macOS 副本妖：`FigureEnrichmentService 2.java` 出现在 src 源码目录（不止 target），
  已删 + clean 重建；bug 记录已更新（编译报「类重复」先查「名字 N.java」副本）。

## 遗留（P2）

- PDFBox 回源重裁（设计档 §5）：v1 用「碎片多图同调用整体描述」替代，规避 bbox 坐标系
  风险；视觉模型 truncated 自检字段已输出但未接重裁回路。
- 文本块 contextual retrieval（LLM 语义前缀）与文本块假设问题：每 chunk 一次 LLM 调用，
  待评测决定；视觉向量轨（multimodal-embedding/ColQwen）同 P2。
- 引用回图（命中 figure 时前端展示原图/回答侧 VLM 读原图）：metadata 已带 figureImages，
  展示链路未做。

## 注意

- 上线后新入库文档才走增强管线；既有 0 文档无重索引需求。
- 视觉调用发生在入库（indexDocument）线程，依赖 model catalog 的 vision 用途解析
  （created_by 用户视角，未配置则降级仅图注）。
