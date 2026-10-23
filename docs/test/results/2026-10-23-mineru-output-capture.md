# 测试结果：MinerU 精准解析结果采集（2026-10-23）

## 环境与范围

- 范围：本地构造的 MinerU 结果 ZIP，不调用在线 MinerU 服务，不使用 Token。
- 命令：`mvn -q '-Dtest=MineruDocumentParserTest' test`

## 结果

- 总计：1 项；通过：1；失败：0；错误：0；跳过：0。
- `KB-MINERU-001`：通过。Markdown、JSON、HTML 和二进制图片均从 ZIP 保留；`full.md` 被选为切片主文本。

## 适配结论与边界

- 在线精准解析 API 默认提供 Markdown 与 JSON；请求中增加官方支持的 `docx`、`html`、`latex` 导出。API 文档说明 HTML 源文件不适用额外格式导出。
- 当前 RAG 切片仍以 Markdown 为主，结果包文件清单及 JSON/HTML 文件数量写入切片元数据；布局/模型 JSON 和其他导出暂不拼入向量文本，以免重复或噪声降低召回质量。
- 本测试不验证远端实际返回文件集合、真实文档解析质量、结构化 JSON 字段语义或最终召回效果；这些需用脱敏的真实 MinerU 结果 ZIP 建回归样本后验证。
