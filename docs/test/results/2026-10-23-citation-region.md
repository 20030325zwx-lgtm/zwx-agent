# 测试结果：引用定位到表格内容区域（2026-10-23）

## 环境与范围

- 后端：本地 Spring Boot + PostgreSQL/pgvector，Flyway V19 已应用。
- 前端：Vite 构建 + Playwright 浏览器验证。
- 数据：构造 MinerU content_list 与引用预览 mock，不调用真实 MinerU/模型。

## 自动化测试

```sh
mvn clean test
npm run build
```

- 后端：210 个测试通过，0 失败，0 错误，1 跳过。
- 前端：构建通过。

## 关键用例

| 用例 | 结果 | 说明 |
| --- | --- | --- |
| `MineruBlockParserTest#parsesTextTableAndFigureBlocksWithPageAndRegion` | 通过 | content_list 转 text/table/figure，页码和表格 headers/rows 正确 |
| `HtmlTableParserTest#expandsRowspanAndColspanIntoHeaderLabeledRows` | 通过 | rowspan/colspan 展开，字段名与值对应 |
| `StructuredKnowledgeChunkerTest#splitsTablesByRowGroupsAndKeepsRegionMetadata` | 通过 | 8 行表格切为 6+2 两组，rowStart/rowEnd/regionLabel 正确 |
| `CitationPreview` mock 流程 | 通过 | 引用卡片显示“表 · 第 12 页 · 第 3-5 行”；弹窗渲染 8 行表格并高亮 3 行；无原始 `<table>` 标签 |
| 旧切片降级 | 通过（手工） | 旧 metadata 无 table 时显示纯文本，并提示旧索引 |

## 适配结论与边界

- 本批次优先覆盖 MinerU + `content_list.json`；Docling/本地解析路径尚未生成来源块。
- 旧文档需要重新解析/重建索引后才会生成 `agent_knowledge_block` 和新 metadata。
- 图片/流程图视觉理解和文本精确区域高亮属于后续批次。
- 尚未用真实在线 MinerU 结果验证不同后端版本的 JSON schema；解析器已做字段兜底。
