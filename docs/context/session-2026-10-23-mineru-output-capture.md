# 会话摘要：MinerU 精准解析结果采集（2026-10-23）

## 改动内容

- MinerU 精准解析请求增加 DOCX、HTML、LaTeX 附加导出格式；API 默认 Markdown/JSON 仍由服务端提供。
- 解析器改为保留下载 ZIP 中全部条目，包括 Markdown、JSON、HTML 和二进制资源，并继续选择 `full.md` 作为 RAG 主文本。
- 切片元数据记录结果包文件清单、JSON/HTML 文件数；原始结构化内容不直接拼入向量文本，避免未经 schema 验证的噪声。
- 新增 MinerU ZIP 结果采集单测及 `docs/test/` 用例与结果记录。
- 用户指定的本地 `codex-token-efficiency` skill 目录已移动至 macOS 废纸篓 `/Users/zhuwenxuan/.Trash/codex-token-efficiency-2026-10-23`，可从废纸篓恢复。

## 验证结果

- `mvn -q '-Dtest=MineruDocumentParserTest' test`：1 项通过。
- 尚未运行真实在线 MinerU 请求；也尚未验证其 JSON schema 对表格切片的实际增益。

## 遗留事项

- 真实 ZIP 样本（脱敏）需验证不同模型/文件类型的结果合同，特别是内容列表与表格单元格结构。
- 当前 ZIP 产物在索引处理期间被采集，但未作为独立文件持久化；检索仍仅消费 Markdown。
- 配置额外格式可能增加结果 ZIP 体积，受现有最大 ZIP 大小限制。

## 环境注意事项

- 保留 `src/main/resources/application-local.yml`，不读取、不改写、不提交 Token。
- 原先 `codex-token-efficiency` 技能文件目前在废纸篓，可恢复。
