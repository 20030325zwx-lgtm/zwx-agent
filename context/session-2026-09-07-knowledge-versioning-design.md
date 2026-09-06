# 会话摘要：2026-09-07 知识库文档版本治理方案

## 改动内容

- 新增 `design/plans/07-knowledge-document-versioning.md`：设计知识库文档版本、有效性、重复检测、原子发布、回滚、冲突处理和版本感知检索方案。
- 更新 `design/plans/README.md`，加入方案 07 索引。
- 本阶段仅新增设计文档，没有修改业务代码、数据库迁移或受保护配置。

## 当前结论

- 现有 `AgentKnowledgeDocumentService` 对同租户、同智能体、同文件名执行删除后重建，能够避免同名新旧同时检索，但无法保留历史版本。
- 未来应以 `logical_key + version_no + lifecycle_status` 管理逻辑文档；索引完成后再原子发布为 `ACTIVE`。
- 检索必须先过滤当前有效版本，再做向量召回和 rerank；不能依赖 rerank 判断新旧版本。

## 验证结果

- 已核对现有 migration、上传/索引服务、租户知识库检索服务和方案索引。
- 未运行构建或测试，因为本阶段没有源码逻辑变更。

## 遗留事项

- 方案尚未实施；后续应先落地 migration、状态字段和单一 ACTIVE 约束，再改上传/发布流程和检索过滤。
- `doc/ROADMAP.md` 仍有历史过期内容，不能作为本方案实施状态依据。

## 环境注意事项

- 不触碰 `src/main/resources/application-local.yml`。
