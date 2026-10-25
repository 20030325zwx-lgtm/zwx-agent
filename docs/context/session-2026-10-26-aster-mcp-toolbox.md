# 会话摘要：aster-agent 独立 MCP 工具箱（2026-10-26 深夜）

**本批改动全部发生在同级新仓库 `~/Desktop/agent/aster-mcp/`（独立 git），主仓库 yu-ai-agent 本体零改动，仅新增本摘要。**

## 背景与定位

用户要求：把市面热门 MCP 能力（联网搜索/抓取、Context7 文档、数据库查询、文件、GitHub、浏览器自动化）做成一个**更全面更专业、可单独部署**的 MCP 服务，放在主项目同级新文件夹，名字用当前 agent 的名字（aster-agent）。与主项目现有 MCP 客户端（`McpServerConfigurationService`，SSE + allowlist）天然互通。

## 交付物（aster-mcp 仓库，commit 8cba351，41 文件）

- `aster-agent/`：Maven 工程（artifactId `aster-agent-mcp-server`），Spring Boot 3.4.5 + Spring AI **1.1.8**（注意：不是主项目的 1.0.0，原因见下），SSE :8124 + stdio 双传输。
- 六组 20 工具：web_search/web_fetch/web_extract_links；docs_search/docs_read（Context7+降级）；db_schema/db_query（只读护栏）；fs_list/fs_read/fs_write（沙箱）；github 四件套（只读）；browser_navigate/eval/screenshot（**纯 JDK WebSocket 直连 headless Chrome CDP**，零 Node 依赖）；kb_documents/kb_retrieval（**直接调主项目 /api/ai/agent-knowledge/***，业务契合）+ toolbox_catalog 元工具。
- 设施：组级开关、UrlGuard/SqlGuard/FsSandbox 三护栏（单测 14/14）、全工具 JSONL 审计、输出限额、`/` 工具目录首页、Dockerfile+compose、launchctl 常驻、冒烟/验证脚本、README、ACCEPTANCE 验收手册、设计文档。

## 验证结果（全绿）

- `mvn package`：单测 14/14；`scripts/smoke-test.sh`（stdio 逐条）：**18/18 通过 0 警告**（含真实 github/web_fetch/headless Chrome 网络调用）。
- `scripts/verify-sse.sh`：initialize → initialized → tools/list 全 200，SSE 流返回 serverInfo aster-agent、20 工具；SSE 模式 9 连发 tools/call 全部响应。
- 已 launchctl 常驻：label `com.zwx.aster-agent-mcp`，日志 `/tmp/aster-agent-mcp.log`，health UP。

## 踩坑记录（重要，后续交接必读）

1. **Spring AI 1.0.0（MCP SDK 0.10.0）SSE 响应挂死**：initialize 后第二个请求服务端收单不回。升级 1.1.8（SDK 0.18.3）解决；协议对主项目 0.10.0 客户端向下兼容。
2. **MCP 会话状态机强依赖 `notifications/initialized`**：漏发该通知时 SDK 表现为静默不回（易误诊为传输故障），客户端必须 initialize → initialized → 再请求。
3. stdio 传输并发灌请求有响应竞态（工具执行成功但部分响应丢失，审计可证）；一问一答 11/11 稳定，SSE 并发无碍——按已知边界记录。
4. **本机是 fake-IP DNS 代理环境**（公网域名解析为 fdfe:… ULA）：UrlGuard 对域名解析路径放行 ULA、字面 IP 全量拦，否则误杀全部外站。
5. 主项目 `app.mcp.allow-private-endpoints` 默认 false，连本工具箱（127.0.0.1:8124）必须在 application-local.yml 增量开启（用户自行添加，见 ACCEPTANCE §4）。

## 遗留事项

1. 未配 `SEARCH_API_API_KEY` 前 web_search 走 DuckDuckGo 兜底（国内常不可达，工具返回指引）；key 与主项目 `search-api.api-key` 同源，配置方法见 ACCEPTANCE §5。
2. kb 组默认无 token，会收到 401/403 指引；配 `ASTER_AGENT_TOKEN`（需知识库权限账号）后可用。
3. db 组未配 `ASTER_DB_URL`（凭据在 application-local.yml，未复制进新仓库——凭用户按 ACCEPTANCE §5 自行注入）。
4. SSE 端点暂无鉴权（内网部署前提）；github 写操作、web_crawl、Streamable HTTP 为后续路线（设计文档 §5）。

## 环境注意事项

- 服务常驻中：`/Users/zhuwenxuan/Desktop/agent/aster-mcp`，端口 8124（与 8123 主服务、8127 图片、8128 文档 MCP 不冲突）。
- 重启/停止：`./scripts/launchctl.sh restart|stop`；构建前先 stop 亦可（端口占用仅警告不影响）。
- 明早验收直接看 `aster-mcp/docs/ACCEPTANCE.md`（5 分钟路径）。
