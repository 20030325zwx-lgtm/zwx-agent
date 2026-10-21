# 目录（TOC）切片后处理

## 问题

真实标准类 PDF 的目录被 Docling 切成多个碎片，例如：

```text
[切片 5] 目 次
前言 .................. II
1 范围 ................ 1
2 规范性引用文件 ...... 1

[切片 6] 目 次
3 术语和定义 .......... 2
4 基本要求 ............ 2
5 职业病危害因素识别 .. 3
```

每个切片都重复“目 次”，且只有 2-4 行目录，检索价值很低。

## 根因

- Docling 使用 `HybridChunker`，默认 tokenizer 为 `sentence-transformers/all-MiniLM-L6-v2`，`max_tokens=256`。
- 目录中的省略号 `......` 会被 tokenizer 计为大量 token。实测四个目录切片分别为 250 / 242 / 240 / 132 tokens，因此每 3 行左右就触发切分。
- `merge_peers=true` 也救不了：两个切片合并后会超过 256 token。
- 每个切片开头的“目 次”是 Docling 自动附加的 section heading。

## 方案

只在 Docling 返回切片后做后处理，不修改全局 `max_tokens`，避免所有文档切片变长。

### 检测

满足任一条件：

- `sectionPath` 归一化后等于或以 `>` 结尾于 `目次`、`目录`、`contents`、`table of contents`；
- 正文至少 2 行同时包含 4 个以上省略号/间隔点，并以页码（数字或罗马数字）结尾。

只处理连续出现的 TOC 切片，避免误伤正文。

### 合并与清洗

- 连续 TOC 切片合并为一个。
- 删除省略号，压缩空白，去掉重复的目录标题。
- 保留条目和页码，文本前缀统一为 `[目录]`。
- 合并 `pageNumbers`。
- 优先从 `json_content.tables[].data.table_cells` 按阅读顺序重建目录，而不是只清洗 chunk 文本。
  - 原因：Docling 把目录误判为表格；triplet 文本会丢失/错序部分单元格，例如安全标准中的“前言”条目在 chunk 文本里消失，但 `table_cells` 仍保留完整顺序。
  - 重建规则：识别 `前言/参考文献/数字章节/附录X（资料性/规范性）` 为条目起点，拼接标题单元格并提取页码。
  - 如果表格重建失败，回退到清洗后的 chunk 文本。
- metadata 标记：
  - `chunking=docling-toc`
  - `contentLayer=toc`
  - `originalChunkCount=<合并前数量>`
  - `tocSource=docling-table|chunk-text`
- 后续如需检索时忽略目录，可按 `contentLayer=toc` 过滤，无需重新解析。

### 配置

`app.document-parser.docling.toc-handling`：

| 值 | 行为 |
| --- | --- |
| `merge`（默认） | 连续 TOC 合并为一个紧凑切片 |
| `drop` | 完全丢弃 TOC 切片，不写入向量库 |
| `keep` | 保留 Docling 原始 TOC 切片 |

非法值回退为 `merge`。

## 改动范围

- `DoclingServeChunkingService`：新增 TOC 检测、合并、清洗和策略开关。
- `application.yml`：新增 `toc-handling` 配置。
- `DoclingServeChunkingServiceTest`：覆盖合并、清洗、`drop` 和正文不受影响。
- 需要重新解析受影响文档才能应用新切片策略。

## 验证结果（2026-10-20）

对三篇真实标准/手册重新解析：

| 文档 | 处理前切片 | 处理后切片 | 原 TOC 碎片 |
| --- | ---: | ---: | ---: |
| 生产安全事故调查工作规范.pdf | 263 | 243 | 21 |
| 铝工业职业病危害预防控制规范.pdf | 109 | 106 | 4 |
| 南京先维员工手册（2024版）.pdf | 86 | 84 | 3 |

铝工业标准的目录最终为 1 个切片，省略号已清理，metadata 为 `chunking=docling-toc`、`contentLayer=toc`、`originalChunkCount=4`。

安全标准的目录从 `table_cells` 重建后包含完整条目（前言、1–7、附录 A–U、参考文献），示例：

```text
[目录]
前言 III
1 范围 1
2 规范性引用文件 1
...
附录U（资料性） 事故整改和防范措施落实情况评估报告 39
DB45/T 2628-2023 参考文献 40
```

## 不做的事

- 不调大全局 `chunking_max_tokens`。虽然可以把目录合成一块，但会让全文所有切片变长，影响检索精度和上下文预算。
- 不在检索阶段临时过滤目录；先保证入库切片本身合理。
