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

1. ~~未配 `SEARCH_API_API_KEY` 前 web_search 走 DuckDuckGo 兜底~~ **已解决（2026-10-26 下午，见下方追加）**：服务自动读取本仓库 `search-api.api-key`，无需再单独配 key。
2. kb 组默认无 token，会收到 401/403 指引；配 `ASTER_AGENT_TOKEN`（需知识库权限账号）后可用。
3. db 组未配 `ASTER_DB_URL`（凭据在 application-local.yml，未复制进新仓库——凭用户按 ACCEPTANCE §5 自行注入）。
4. SSE 端点暂无鉴权（内网部署前提）；github 写操作、web_crawl、Streamable HTTP 为后续路线（设计文档 §5）。

## 环境注意事项

- 服务常驻中：`/Users/zhuwenxuan/Desktop/agent/aster-mcp`，端口 8124（与 8123 主服务、8127 图片、8128 文档 MCP 不冲突）。
- 重启/停止：`./scripts/launchctl.sh restart|stop`；构建前先 stop 亦可（端口占用仅警告不影响）。
- 明早验收直接看 `aster-mcp/docs/ACCEPTANCE.md`（5 分钟路径）。

## 追加：搜索 key 单一来源（2026-10-26 下午）

**改动内容**（全部在 `aster-mcp` 仓库，主仓库仍零代码改动）
- `aster-agent/src/main/resources/application.yml`：新增 `spring.config.import: optional:file:${ASTER_MAIN_LOCAL_YML:~/Desktop/agent/yu-ai-agent/src/main/resources/application-local.yml}`；web 组改为
  `provider: ${SEARCH_API_PROVIDER:${search-api.provider:searchapi}}`、`api-key: ${SEARCH_API_API_KEY:${search-api.api-key:}}`。
  优先级=本服务显式环境变量 → 主项目同名配置 → 内置默认；密钥不复制进 aster-mcp 任何文件（遵守本仓库"不外抄 secret"约束）。
- README（配置表 + 新增「密钥单一来源」小节 + 已知边界 2）、docs/ACCEPTANCE.md（§5 搜索行改为"已默认生效"、§6 排查行）同步。

**为什么不做成 shell 注入**：`launchctl submit` 的作业里 `bash scripts/xxx.sh` 会被 macOS 以 `Operation not permitted`（exit 126）拒绝执行 `~/Desktop` 下的脚本，而直接 `exec java -jar` 可以；因此把读取动作放进 Spring 配置层，launchctl/run.sh/IDE 三种启动方式都天然生效，脚本保持原样。

**验证结果**
- `./scripts/build.sh` 通过；`./scripts/launchctl.sh install` 后 health UP，PID 91697。
- `GET /` 配置状态显示 `provider tavily 已配置 key`（tavily 来自主项目本地配置，不是本服务默认 searchapi）。
- stdio 实调 `web_search`（query=Spring AI MCP server 1.1 release）：`isError=false`，返回 spring.io 等真实结果，不再走 DuckDuckGo 兜底。

**遗留事项**
- db 组沿用同样机制在技术上可行（导入的文件已含 `spring.datasource.*`），但**故意没做**：当前 SSE 端点无鉴权，自动接上主库凭据等于开一个无鉴权的库查询入口。顺序应为"先补入站 token 校验 → 再考虑 db"。
- kb 组 `ASTER_AGENT_TOKEN` 仍需人工登录换取；`ASTER_AGENT_KEY` 默认 `test`（n8n ingestion 试点范围，`CurrentActor` 同时接受 `test`/`super`），要看超级智能体的知识库需显式设为 `super`。

**环境注意事项**
- 主项目 `application-local.yml` 仍是只读引用：不修改、不提交、不打印值；换 key 只需改主项目那一份，本服务重启即跟随。
