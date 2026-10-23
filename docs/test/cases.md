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
