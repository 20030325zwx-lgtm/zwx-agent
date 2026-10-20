# 回答引用切片预览

> 状态：已实施（2026-10-20）。前端：`ChatRoom.vue`；后端：`GET /api/ai/agent-knowledge/chunks/{chunkId}`。

## 1. 目标

超级智能体回答中的 `[文件名#切片号]` 不应只是纯文本。用户需要在回答下方直接看到：

- 本次回答实际引用了哪些文档和切片；
- 每个切片的一段摘要；
- 点击后阅读该切片的完整原文。

## 2. 数据来源

- 回答内容：助手消息 Markdown，引用格式 `[09-jiangsu.md#3]`。
- 检索 trace：`AgentKnowledgeRagMessage.rag_retrieval_trace` 中的 candidates，包含 `filename`、`chunkIndex`、`documentId`、`excerpt`、`selected`。
- 切片原文：`agent_knowledge_vector` 持久化记录，`documentId` 即向量切片主键。

## 3. 前端流程

```mermaid
flowchart TD
    A[助手回答] --> B[正则提取 filename + chunkIndex]
    B --> C{在 RAG trace candidates 中匹配}
    C -->|匹配| D[渲染引用卡片 + 摘要]
    C -->|未匹配| E[渲染未找到切片的提示卡]
    D --> F[点击卡片]
    F --> G[GET /agent-knowledge/chunks/{chunkId}]
    G --> H[弹窗展示完整切片原文]
```

- 引用去重：同一 `filename#chunkIndex` 只展示一次。
- 优先匹配 `selected=true` 的候选；没有则使用第一个同切片候选。
- 切片原文按 `chunkId` 缓存，避免重复请求。
- 切片被删除或重建时接口返回 404，弹窗显示明确提示，不阻塞回答阅读。

## 4. 后端接口

```text
GET /api/ai/agent-knowledge/chunks/{chunkId}?agentKey=super
```

- 需要 JWT；按当前用户 tenant 与 `agentKey` 过滤，不要求 ADMIN。
- 返回 `chunkId`、`documentId`、`filename`、`chunkIndex`、`content`。
- 只返回当前租户、当前智能体可见的切片；未命中返回 404。

## 5. 已知边界

- 只对包含 `rag_retrieval_trace` 的回答生效；没有 trace 的历史消息不会生成引用卡片。
- 模型编造的引用若不在 trace 中，会显示“未找到可预览的切片”。
- 只解析 `[filename#chunkIndex]` 格式；其他引用格式需要后续扩展。
- 切片预览展示的是当前持久化内容；文档重新索引后旧切片可能返回 404。
