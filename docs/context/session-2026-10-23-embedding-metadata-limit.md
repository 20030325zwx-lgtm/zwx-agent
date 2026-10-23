# 会话摘要：修复切片 embedding 超 token（2026-10-23）

## 问题

- 文档 `个人绩效承诺书PBC-x2292-朱文轩(1).pdf` 索引失败：`Tokens in a single document exceeds the maximum number of allowed input tokens`。
- 文档状态 `FAILED`、`chunk_count=0`，表 `agent_knowledge_block` 已生成 7 个文本块和 9 个表格块，说明解析与切片本身正常，失败发生在 embedding 阶段。

## 根因

- DashScope `DashScopeEmbeddingModel.embed(Document)` 默认使用 `MetadataMode.EMBED`，会把文档 metadata 拼进待 embedding 的文本。
- MinerU 结果采集把完整 `mineruResultFiles` 文件清单写进了每个切片的 metadata；PDF 图片/JSON 文件较多时，单个切片的 embedding 输入被 metadata 放大，超过模型 token 上限。
- 表格/文本正文本身并不大（表格块最大约 8.5K 字符，文本块最大约 155 字符）。

## 修复

- `application.yml` 增加：
  ```yaml
  spring.ai.dashscope.embedding.metadata-mode: NONE
  ```
  只嵌入正文，不再把 metadata 拼进 embedding。
- 切片 metadata 不再写入完整 `mineruResultFiles` 列表，改为 `mineruResultFileCount`；JSON/HTML 数量保留。
- 完整文件清单仍保存在 OSS manifest 中，便于排查。

## 验证

- 后端 212 个测试通过（1 跳过）。
- 新 JAR 已重启，`/api/health` 返回 200。
- 需要用户在知识库管理页对失败文档重新解析一次；索引会重新调用 MinerU、生成 block 并重新 embedding。

## 遗留事项

- 本轮改动尚未提交。
- 如果后续 embedding 模型/PGVector 批量策略变化，仍建议保留“embedding 输入长度预检 + 分批降级”的兜底。
