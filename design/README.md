# 设计文档

- [当前智能体架构](./agent-architecture.md)
- [超级智能体跨重启续跑](./2026-10-20-super-agent-run-resume.md)
- [SSE 断线重连与事件回放](./2026-10-20-sse-reconnect-replay.md)
- [工具调用幂等](./2026-10-20-tool-call-idempotency.md)
- [会话删除应用内确认](./2026-10-20-in-app-confirm-dialog.md)
- [回答引用切片预览](./2026-10-20-answer-citation-preview.md)
- [Docling Serve 本地部署](./2026-10-20-docling-serve-deployment.md)
- [Docling Serve 接入入库链路](./2026-10-20-docling-serve-integration.md)
- [改造实施方案索引](./plans/README.md)

本目录保存相对稳定的架构、模块边界、数据流和设计决策。实现细节以源码为准。

**命名规范**：设计文档文件名必须为 `YYYY-MM-DD-<设计主题>.md`（日期 + 主题）。早期 01~08 编号文档为历史档案，保留原名不再改名。
