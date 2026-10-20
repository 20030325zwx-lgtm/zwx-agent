# 会话摘要：应用内确认弹窗与引用切片预览（2026-10-20）

## 改动内容

### 删除确认改为应用内弹窗

- 新增 `zwx-agent-frontend/src/components/ConfirmDialog.vue`：Teleport 到 body、遮罩、危险操作样式、Esc 取消、Enter 确认、打开自动聚焦。
- 替换 4 处原生 `window.confirm()`：
  - 超级智能体删除会话
  - 旅游规划专家删除会话
  - 情感分析大师删除会话
  - MCP 服务删除
- 删除执行中的会话时不弹窗，避免删除运行中的 run。

### 回答引用切片预览

- 后端新增 `GET /api/ai/agent-knowledge/chunks/{chunkId}?agentKey=...`：
  - 按 JWT tenant + agentKey 过滤 `agent_knowledge_vector`；
  - 返回 `chunkId`、`documentId`、`filename`、`chunkIndex`、`content`；
  - 不要求 ADMIN，未命中返回 404。
- 新增 `AgentKnowledgeChunkPreview` 与 `AgentKnowledgeDocumentService.getChunk`。
- `ChatRoom.vue`：
  - 从回答内容解析 `[文件名#切片号]`；
  - 与 `ragTrace.candidates` 按 filename + chunkIndex 匹配，优先 selected；
  - 在回答下方渲染“引用资料”卡片（文件名、切片号、摘要）；
  - 点击卡片请求切片原文并在弹窗中预览，带缓存和 404/失败提示；
  - 支持 Esc 关闭。
- 新增前端 API `getAgentKnowledgeChunk`。

### 文档与 README

- 新增设计文档：
  - `design/2026-10-20-in-app-confirm-dialog.md`
  - `design/2026-10-20-answer-citation-preview.md`
- 更新 `design/README.md` 索引。
- 更新根 `README.md`：当前能力、引用切片预览接口、设计文档链接。

## 验证

- 后端 `mvn -o -q -DskipTests package` 成功，服务重启后 `/api/health` 返回 `ok`。
- 前端 `npm run build` 成功。
- 真实知识库问答冒烟：
  - 回答包含 `[09-jiangsu.md#3]`、`#2`、`#5`；
  - RAG trace 中三个切片均能匹配到 candidate；
  - 调用 `GET /agent-knowledge/chunks/{chunkId}?agentKey=super` 返回完整切片原文。
- `git diff --check` 无错误。

## 遗留事项

- 引用解析只支持 `[filename#chunkIndex]` 格式；其他引用样式需要扩展。
- 没有 RAG trace 的旧消息不会生成引用卡片；模型编造的引用会显示“未找到可预览切片”。
- 文档重新索引后旧切片可能 404，弹窗会提示但不会回退到新版本切片。
- 本轮改动尚未提交。
- 之前 SSE/工具幂等改动也仍未提交；上一提交为 `b616f70`。

## 环境注意事项

- `src/main/resources/application-local.yml` 未读取、编辑、复制或加入 Git。
- 后端由 launchctl 管理；本轮已重新打包并重启。
- 本地 PostgreSQL 已应用 V11、V12、V13。
