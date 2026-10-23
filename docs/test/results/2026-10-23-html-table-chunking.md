# 测试结果：HTML 表格结构化切片（2026-10-23）

## 环境与范围

- 验证提交：`0756e4b`（改进 HTML 表格切片并记录知识质量计划）。
- 范围：知识库切片器的两个针对性单元测试；未运行完整后端测试套件。
- 命令：

```bash
mvn -q '-Dtest=MyTokenTextSplitterTest#expandsHtmlTableRowspansIntoHeaderLabeledRecords+keepsLongHtmlTableRowsWholeAcrossChunks' test
```

## 结果

- 总计：2 项；通过：2；失败：0；错误：0；跳过：0。
- `KB-TABLE-001`：通过。数据行中的 `rowspan` 会继承到后续记录；多级表头的 `rowspan` / `colspan` 被合并为字段路径；切片内容不再保留 `<table>` 标签。
- `KB-TABLE-002`：通过。无 `<thead>` 表格将首行作为列名；长记录分布到多个切片时，记录行、字段名与单元格文本保持完整。

## 发现与未覆盖风险

- 本结果只证明上述输入的结构转换与切片边界，不验证 MinerU 在线服务、真实文件解析质量、向量召回准确率或模型最终回答正确性。
- 跨页续表、OCR 错误、多层复杂表头、无表头数据表和超长单条记录仍需单独构造样本验证。
- 本次没有运行全量后端测试。
