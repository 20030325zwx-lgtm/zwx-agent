# 测试用例清单

用例来源以仓库中的自动化测试代码为准。本清单选取跨版本需要复核的行为，避免替代或复制整套单元测试。

## 知识库 HTML 表格切片

| ID | 场景 | 关键断言 | 自动化用例 |
| --- | --- | --- | --- |
| KB-TABLE-001 | 含 `rowspan` 的表格行 | 合并列值出现在其覆盖的每条记录；字段名与值保持在同一条记录；输出不残留原始 HTML 标签 | `MyTokenTextSplitterTest#expandsHtmlTableRowspansIntoHeaderLabeledRecords` |
| KB-TABLE-002 | 无 `<thead>` 且记录较长的表格 | 首行作为表头；多条记录跨多个切片时，每条完整记录及字段名都保留，不拆断单元格内容 | `MyTokenTextSplitterTest#keepsLongHtmlTableRowsWholeAcrossChunks` |

### 回归检查建议

- 对照原文件逐字段核验切片，特别检查合并单元格的继承范围和多级表头。
- 用具体问题检索后核对召回记录是否同时包含问题所需字段、值和适用范围；只命中同一关键词不算通过。
- 记录解析器、文件类型、表格页码/编号以及原文核对结论。扫描件需单独检查 OCR 质量。
- 复杂跨页表、无表头表、嵌套表和超长单个单元格尚未由当前自动化用例完整覆盖，遇到时标记为待专项验证。

## MinerU 精准解析结果采集

| ID | 场景 | 关键断言 | 自动化用例 |
| --- | --- | --- | --- |
| KB-MINERU-001 | MinerU 结果 ZIP 含 Markdown、JSON、HTML 和二进制资源 | 选择 `full.md` 作为主文本；完整保留所有文件和二进制字节；可按扩展名读取文本产物 | `MineruDocumentParserTest#collectsEveryMineruZipOutputAndSelectsFullMarkdown` |

## 引用区域定位

| ID | 场景 | 关键断言 | 自动化用例 |
| --- | --- | --- | --- |
| KB-CITE-001 | MinerU content_list 中的表格块 | 表格转为 headers/rows；按行组切片；metadata 带 blockId、页码、rowStart/rowEnd 和 regionLabel；切片文本不含 HTML 标签 | `MineruBlockParserTest#parsesTextTableAndFigureBlocksWithPageAndRegion`、`HtmlTableParserTest#expandsRowspanAndColspanIntoHeaderLabeledRows`、`StructuredKnowledgeChunkerTest#splitsTablesByRowGroupsAndKeepsRegionMetadata` |
| KB-CITE-002 | 引用弹窗展示表格 | 渲染真实表格并高亮命中行；显示区域标签；页面不出现原始 `<table>` 标签 | `ChatRoom` + `CitationPreview`，Playwright mock 流程（见 `docs/test/results/2026-10-23-citation-region.md`） |
| KB-CITE-003 | 旧切片/未重建索引 | 引用仍能打开；原始 HTML 被降级为纯文本并标记“旧索引” | 手工回归：使用旧 metadata 切片打开 `CitationPreview` |

### 回归检查建议

- 重新解析文档后，对照原文件确认表格行号、合并单元格和页码一致。
- 引用回答必须能定位到具体行；只显示整张表或整个切片不算通过。
- 旧文档未重建索引时，确认预览不出现 HTML 标签。
