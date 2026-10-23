# 测试结果：知识库文件删除（2026-10-23）

## 环境与范围

- 后端：本地 Spring Boot + PostgreSQL/pgvector。
- 前端：Vite 构建 + Playwright mock。
- 范围：删除确认、API 调用、页面刷新；后端清理逻辑用单元测试验证。

## 结果

- 后端：214 个测试通过（1 跳过）。
- 前端：构建通过。
- `AgentKnowledgeDocumentDeleteTest`：通过，验证切片、来源块、文档记录、原文件对象和 MinerU 目录对象均被清理。
- Playwright：主行删除按钮、确认弹窗、DELETE 请求和列表刷新均通过。

## 边界

- 删除活跃版本后不会自动晋升旧版本；需要额外策略时再实现。
- 未在真实 MinIO 上执行大批量前缀删除压测；按 1000 条分页处理。
