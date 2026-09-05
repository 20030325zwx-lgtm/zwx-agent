# 方案 02：工具安全边界与沙箱

## 1. 目标

LLM 可调用的每个工具都有明确的边界：能读哪些路径、能写到哪里、能访问哪些网络、执行多久。完成后：

- 提示注入（知识库文档、网页内容、MCP 工具描述）无法导致任意文件读写、SSRF 或命令执行。
- 工具文件按租户/会话隔离，用户只能取回自己会话生成的文件。
- 在生产 Linux 上所有工具行为一致，不存在"写死 cmd.exe 导致静默失效"。

## 2. 现状问题（审计证据）

| 问题 | 位置 |
| --- | --- |
| `TerminalOperationTool` 写死 `cmd.exe /c`，无白名单/超时/输出截断；注册进 `allTools` 供 Manus 使用 | `tools/TerminalOperationTool.java:16-21`、`tools/ToolRegistration.java:27,34`、`AiController.java:443` |
| `FileOperationTool` 直接 `FILE_DIR + "/" + fileName`，无 normalize/startsWith，可路径穿越读写 | `tools/FileOperationTool.java:17、29` |
| `ResourceDownloadTool` 任意 URL + fileName（SSRF、任意路径写、无大小上限） | `tools/ResourceDownloadTool.java:17-25` |
| `WebScrapingTool` 无协议/内网限制，内网内容回传 LLM 形成外泄通道 | `tools/WebScrapingTool.java:14-17` |
| Manus 文件目录全局共享（file/download/pdf 同一棵树，无会话隔离），`getManusFile` 仅验会话存在 | `constant/FileConstant.java:11-21`、`PDFGenerationTool.java:26-27`、`AiController.java:513-527` |
| MCP 注册任意 URL（不拦 localhost/私网），`test()` 可作内网探测器；每次 manus 对话同步串行连接所有 MCP server | `mcp/McpServerConfigurationService.java:59-85、118-128、131-134` |
| `getManusFile` 以 `inline` 返回任意探测到的 Content-Type，存在存储型 XSS 面 | `AiController.java:513-527` |

## 3. 方案设计

### 阶段 1：文件工具边界（改动最小、收益最大）

1. 新建 `ToolSandbox` 组件：统一管理工具根目录，提供 `resolveInSandbox(String relativePath) -> Path`，内部做 `normalize()` + `startsWith(root)` + 拒绝符号链接；所有文件类工具强制经过它。
2. `FileOperationTool`：
   - 读/写均走 `ToolSandbox`；写操作限制单文件 5MB、单次会话累计 50MB。
   - 输出给模型的消息只报相对路径，不暴露服务器绝对路径。
3. `ResourceDownloadTool`：
   - URL 协议白名单 http/https；解析目标域名后的 IP 拒绝 loopback/私网/链路本地（127.0.0.0/8、10/8、172.16/12、192.168/16、169.254/16、::1、fc00::/7）；跟随重定向后复检（自定义 `RedirectStrategy`）。
   - 下载大小上限 20MB（流式计数超限中止）；文件名走 `ToolSandbox`。
4. `PDFGenerationTool` 输出同样进 `ToolSandbox`。

### 阶段 2：目录隔离与文件取回

1. 目录结构改为 `FILE_SAVE_DIR/{tenantId}/{conversationId}/{toolType}/`（依赖方案 01 提供身份；一期可先 `{conversationId}/`）。
2. 各工具通过 ThreadLocal 或显式参数拿到当前 `conversationId`（推荐显式：工具注册时按请求绑定，参考 Manus 每请求 `new` 实例的做法，在构造时注入工作目录）。
3. `getManusFile` 校验：路径必须解析在 `root/{tenantId}/{conversationId}/` 内且 conversationId 归属当前用户；`Content-Disposition` 对 HTML/SVG/XML 强制 `attachment`，白名单以外的类型不 inline。

### 阶段 3：终端工具处置

决策点：**生产默认移除 `TerminalOperationTool`**。

1. `ToolRegistration` 拆分为 `safeTools`（文件读写受限版、PDF、搜索、抓取受限版、下载受限版）与 `elevatedTools`（终端、原始下载）；`elevatedTools` 默认不注册进 `allTools`，仅本地开发 profile 开启。
2. `cmd.exe` 硬编码改为跨平台（POSIX 用 `sh -c`），加 30 秒超时（`waitFor(timeout)` + `destroyForcibly`）、输出截断 8KB、命令白名单（`ls/cat/pwd` 等只读命令列表，可配置）。
3. 中期（ROADMAP 对应项）：终端执行迁入独立容器（挂载同一沙箱目录的 sidecar 容器，`docker exec` 或长期运行的工作容器），Java 进程本身永远不直接执行模型给出的命令。

### 阶段 4：MCP 与网络工具收敛

1. MCP endpoint 校验增加：解析域名后拒绝私网/loopback；`test()` 与 `toolsFor()` 均套用；管理接口依赖方案 01 的 ADMIN 角色。
2. MCP 连接加超时（现有 init 超时保留）+ 并发上限 + 失败熔断：单个 server 连接失败不影响其余 server 工具注入，并记录事件到执行轨迹。
3. `WebScrapingTool` 复用下载工具的 URL 校验器；返回内容截断（现有基础上增加总字节上限）并包裹明确标记：

   ```
   <untrusted_content source="https://...">
   ...抓取内容...
   </untrusted_content>
   ```

   系统提示词声明该标签内内容为资料而非指令。

### 阶段 5：授权矩阵与测试

1. 工具分级常量：`SAFE` / `ELEVATED`；`ToolRegistration` 按 `agentKey × tenant 配置 × profile` 决定注入集合（与 Skill 体系的按租户启停机制对齐）。
2. 新增测试：
   - `FileOperationToolTest`：`../../application-local.yml`、绝对路径、符号链接均被拒绝。
   - `ResourceDownloadToolTest`：内网 IP、重定向到内网、超大文件均被拒绝。
   - `McpServerConfigurationServiceTest`：私网 endpoint 注册被拒。
   - `ManusFileAccessTest`：跨会话/跨租户取文件 404。

## 4. 交付拆分

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| 批次 1 | ToolSandbox + 三个文件工具改造 | 穿越测试全绿 |
| 批次 2 | 会话级目录 + getManusFile 归属校验 | 跨会话取文件 404 |
| 批次 3 | 终端工具默认移除 + 白名单/超时版 | 生产镜像无终端工具；本地 profile 可用 |
| 批次 4 | MCP/抓取/下载网络校验 + 不可信内容标记 | SSRF 测试全绿 |

## 5. 风险与决策点

- 终端工具移除后 Manus 任务能力下降：受影响的任务类型需要在发布说明中声明；白名单版作为过渡。
- 私网拦截需要防 DNS rebinding：一期按"解析后 IP 复检"实现；如后续有内网集成需求，引入显式 allowlist 配置。
- 目录结构变更是破坏性改动：旧 `FILE_SAVE_DIR` 下已生成文件仅保留下载兼容期（按旧规则可读、不可写）。
