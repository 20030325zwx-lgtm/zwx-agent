# MCP 连接管理与工具授权

## 目标与参考模式

采用 MCP 客户端/宿主常见管理流程：配置连接与认证、测试连接、自动发现服务端工具及其 JSON Schema、由管理员控制哪些工具可供 Agent 调用。工具参数仍由 MCP 服务端 schema 定义，管理页面展示参数，不再要求重复录入参数表单。

本项目当前锁定的 MCP Java SDK 0.10.0 提供 HTTP SSE client transport；首版只开放 SSE。Streamable HTTP 和 stdio 需要升级/增加 transport 并单独验证后再支持。

## 本批实现

- 配置支持无认证、Bearer Token、自定义 Header；密钥 AES-GCM 加密存储，列表只回传掩码和是否已配置。
- 连接测试返回工具名称、说明、参数属性、必填字段与 JSON Schema。
- 新增配置的工具 allowlist 为空数组，默认不开放工具；从旧版本迁移的行 `allowed_tools IS NULL` 暂保留旧行为，避免升级后现有 Agent 能力突然消失。管理员编辑并保存显式授权后转为 allowlist。
- 主调用链按 allowlist 过滤工具回调。

## 安全和后续

- MCP endpoint 沿用公网 HTTP(S) URL 校验；认证 Header 仅在 MCP HTTP 请求上注入，不返回明文。
- 仍需后续补充服务状态/最后错误持久化、工具清单变化检测与重新授权提示、认证刷新 OAuth、Streamable HTTP、资源级授权审计及端到端工具调用测试。
- 兼容旧配置期间其全部工具仍然可用，运维应逐项检查旧配置并完成显式授权后再收紧迁移策略。
