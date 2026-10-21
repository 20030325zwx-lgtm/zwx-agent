# 会话摘要：后端包结构重组（2026-10-21）

## 改动内容

- 将平铺的顶层包按业务域重组为：
  - `agent.core/advisor/execution/graph/hook/model`
  - `chat.conversation/memory`
  - `knowledge.document/retrieval/image/storage`
  - `capability.tools/mcp/skills`
  - `platform.config/security/settings/workspace`
  - `web.controller`
  - `common`
- `rag` 按职责拆分为文档处理、检索与图片理解。
- `configuration` -> `platform.settings`；`chatmemory` + `memory` -> `chat.memory`；`execution` -> `agent.execution`；`controller` -> `web.controller`。
- 删除 `demo` 示例包。
- 同步更新 `META-INF/spring.factories` 与 Swagger `packages-to-scan`。
- 测试类随主代码迁移到对应包，保持包内可见性。

## 验证结果

- `mvn clean test-compile` 通过。
- 全量 `mvn test`：198 个测试，0 失败，0 错误，1 跳过。
- `mvn clean package -DskipTests` 通过；后端重启后 `/api/health` 返回 `ok`。
- 主代码 150 个 Java 文件，测试 49 个 Java 文件。

## 遗留事项

- 包结构设计说明见 `docs/design/2026-10-21-package-structure.md`。
- 本轮改动尚未提交。
