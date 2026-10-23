# 会话摘要：修复切片 embedding 超 token（2026-10-23）

## 问题

- 文档 `个人绩效承诺书PBC-x2292-朱文轩(1).pdf` 索引失败：`Tokens in a single document exceeds the maximum number of allowed input tokens`。
- 文档状态 `FAILED`、`chunk_count=0`，表 `agent_knowledge_block` 已生成 7 个文本块和 9 个表格块，说明解析与切片本身正常，失败发生在 embedding 阶段。

## 根因

- PBC 表格首行是 colspan 覆盖全表的说明文字，`HtmlTableParser` 把它当成表头；7 列都得到 757 字的长标签，并在每条记录里重复，单个切片远超 `text-embedding-v2` 的 2048 token 上限。
- 同时 DashScope embedding 默认 `MetadataMode.EMBED` 会把 metadata 拼进输入，完整 `mineruResultFiles` 文件清单进一步放大了输入。

## 修复

- `HtmlTableParser` 跳过覆盖整列的说明/标题首行，改用下一行作为表头，并将表头长度限制在 120 字。
- `application.yml` 设置：
  ```yaml
  spring.ai.dashscope.embedding.metadata-mode: NONE
  ```
  只嵌入正文，不再把 metadata 拼进 embedding。
- 保留 `text-embedding-v2`，避免改变已有向量的语义空间；v4 在该 SDK 版本下 fixed 1024 维，和现有 1536 维表不兼容。
- 切片 metadata 不再写入完整 `mineruResultFiles` 列表，改为 `mineruResultFileCount`；JSON/HTML 数量保留。
- 完整文件清单仍保存在 OSS manifest 中，便于排查。

## 验证

- 后端 213 个测试通过（1 跳过）。
- 用该文档已存 `html_content` 重新解析：表头恢复为“主目标描述/主目标权重/…”，6 行切片正文约 843 字，低于 2048 token 上限。
- 新 JAR 已重启，`/api/health` 返回 200。
- 需要用户重新解析失败文档；其他已成功文档不受影响。

## 遗留事项

- 本轮改动尚未提交。
- 如果后续 embedding 模型/PGVector 批量策略变化，仍建议保留“embedding 输入长度预检 + 分批降级”的兜底。
