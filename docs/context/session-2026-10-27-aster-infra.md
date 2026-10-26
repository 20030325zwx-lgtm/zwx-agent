# 会话摘要：aster-infra 基础设施服务期 1 交付（2026-10-27）

> 接手请优先读权威交接文档 `handoff-2026-10-27-aster-infra.md`（服务版图/验证手册/期 2 文件级指引/踩坑清单）；本篇是会话流水。

**本批改动主要发生在同级新仓库 `~/Desktop/agent/aster-infra/`（独立 git，本批已 init + 首个 commit）；主仓库仅新增设计文档与本摘要（主仓库代码零改动）。**

## 背景与定位

接续 2026-10-27 设计文档 `docs/design/2026-10-27-aster-infra.md`（沙箱/出网网关/模型网关三合一，先经用户确认的设计评审补了 DNS rebinding 方案、SearXNG JSON 格式、OrbStack、孤儿容器等 10+ 处考虑不到位项）。本期交付：`/sandbox` 全量 + `/egress` search/fetch，含一键 Docker 部署。用户要求：服务须支持一键 docker 部署并自测。

## 交付物（aster-infra 仓库，:8125）

- Spring Boot 3.4.5 + Java 21，纯 REST 不依赖 Spring AI；包 `com.zwx.asterinfra.{common,sandbox,egress}`。
- **/sandbox**：一次性 Docker 容器执行（`--network none` + `--memory 256m --cpus 1 --pids-limit 64 --read-only` + tmpfs /tmp + no-new-privileges），python/node/shell 三语言（镜像 python:3.12-slim / node:22-slim / 自建 aster-sandbox-cli）；`docker` CLI 经 `asterinfra.sandbox.docker-bin` 显式路径（launchctl 极简 PATH 兼容）；容器打 `asterinfra.run` label，@PostConstruct + @Scheduled 每 5 分钟清扫孤儿容器与过期工作区（跳过活跃 run）；artifact 下载 `GET /api/sandbox/artifact/{runId}?path=`（runId 正则 + path 归一化双重越界校验）。
- **/egress**：EgressGuard（scheme 白名单、localhost/.local 拒绝、字面 IP 私网全量拒含 ULA、域名解析逐 IP 校验）+ Apache HttpClient5 自定义 `GuardingDnsResolver` 在连接层固定已校验 IP（TLS/SNI 按原域名，消除 guard-then-fetch 竞态）——这是设计评审时发现 JDK HttpClient 做不到的关键点；搜索回退链 searxng(本机 sidecar, directClient)→tavily→searchapi（key 经 spring.config.import 从主仓库 local yml 单一来源读取）；fetch 仅 HTML/纯文本（PDF 归 document-mcp、JS 渲染归 browser-mcp），jsoup + 移植 aster-mcp 的 HtmlToMarkdown（修了原版表格分隔与 <30 字符回退误吞结构两个小瑕疵）；Caffeine 缓存（fetch 按 url+maxBytes，10min）、每 token 滑窗限速（search 10/min、fetch 30/min）、幂等重试 2 次指数退避。
- **common**：单一 Bearer token（env `ASTER_INFRA_API_TOKEN` → 主仓库 local yml `asterinfra.api-token`；未配置时受保护端点整体 503）、错误 token 20 次/分钟临时封禁、JSONL 审计（对齐 aster-mcp ToolAudit，10MB 滚动×5，token 指纹）、Micrometer Prometheus `/api/metrics`、`/api/health`（公开）+ `/api/status` 自检。
- **部署**：`Dockerfile`（多阶段构建，COPY docker:28-cli 的 docker CLI）+ `docker-compose.yml`（aster-infra + searxng + uptime-kuma[ops profile]，端口仅绑 127.0.0.1）+ `deploy/searxng/settings.yml`（`use_default_settings` 最小覆盖 + **formats 显式加 json**）+ `.env.example`；`scripts/{build,run,launchctl,smoke-test,build-cli-image}.sh`（launchctl 模式复刻 aster-mcp，显式 `--server.port=8125`）。
- 单测 9 个类 48 例：EgressGuard（私网/ULA/V4-mapped/混合解析/字面 IP 严格口径）、GuardingDnsResolver、SearchFacade 回退链、HtmlToMarkdown、AuditWriter 轮转、RateLimiter、AuthRateGuard、SandboxService 校验与 docker 命令旗标、MockMvc 鉴权/artifact 穿越（含无 token 上下文 503）。

## 验证结果（全绿）

- `mvn test`：48/48 通过。
- 冒烟 `scripts/smoke-test.sh` **17 项 × 2 种模式全绿**：宿主模式（run.sh + env token）、容器化模式（compose up，沙箱经 docker.sock sibling 执行 + 共享卷）。覆盖：鉴权 401/503、三语言执行、断网验证（socket 外连超时）、超时熔断、产物写+严格下载、环回/字面 ULA/scheme 拦截、example.com 真实抓取含 fetchedAt 引用元数据、searxng 搜索、metrics、status。
- 孤儿清扫实测：预置带 label 假容器，服务启动即被回收。
- 当前常驻状态：**compose 栈运行中**（aster-infra + searxng，:8125/:8888 均仅绑本机回环）；token 在 aster-infra/.env（gitignored，本地生成 48 hex）。

## 自测发现并修复的问题（详见 aster-infra/docs/bugs/bug-2026-10-27-*.txt）

1. **compose 顶级 volume 加项目前缀**：实际卷名 `aster-infra_asterinfra-workspaces` ≠ `SANDBOX_VOLUME_NAME` 传的 `asterinfra-workspaces`，docker 隐式建空卷导致容器模式沙箱看不到 main.py。修复：顶级卷显式 `name:` 固定。
2. **`docker version --format {{.ServerVersion}}` 在 client 结构上执行模板报错** → 探针改 `docker info`。
3. 冒烟产物断言假阳性（artifacts[0] 按字母序取到 main.py，代码文本含同串）→ 改为精确取 out.txt。
4. 代码层面顺手修：全局异常处理器曾把 NoResourceFoundException 兜成 500（404 场景），补 404/405/400 映射；EgressGuard 双构造器加 @Autowired；AuditWriter 对不可变 Map 防御拷贝。

## 遗留事项（期 2/3）

1. 主服务与 aster-mcp 接入未做（期 2）：主服务 `app.sandbox.mode=infra` 切换 + `SandboxTerminalTool`、aster-mcp sandbox 工具组与 web 组改走 /egress；主服务侧 HTTP 读超时须 70s > cap 60s。
2. `/egress` download 代理、uptime-kuma 监控项配置（容器起了但监控项要在 :3001 首次手工建账号配置）未做。
3. `/llm` 模型网关第三期；基础镜像 digest 固定、browser-mcp 接入评估同期。
4. 用户动作：若要宿主 launchctl 常驻，先往主仓库 `application-local.yml` 增量加 `asterinfra: api-token: <串>`（我按规矩没动该文件），再 `scripts/launchctl.sh install`；注意与 compose 栈共用 8125，二选一。
5. searxng settings.yml 的 secret_key 是本地开发占位值，公网部署前须更换。

## 环境注意事项

- aster-infra 独立 git 仓库已建（首 commit）；`logs/`、`sandbox/`、`.env` 已 gitignore。
- 主仓库 `application-local.yml` 全程未动（本服务只读 import 引用 `search-api.*`，新键 `asterinfra.api-token` 待用户自行增量添加）。
- 构建顺序：改后端 → `bash scripts/build.sh`（= mvn package）→ 容器模式 `docker compose up -d --build`；宿主模式 `scripts/launchctl.sh restart`。
