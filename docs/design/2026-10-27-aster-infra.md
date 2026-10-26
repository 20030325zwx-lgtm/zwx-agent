# aster-infra 基础设施服务设计（沙箱 / 出网网关 / 模型网关三合一）

> 2026-10-27 新建。实现放在同级独立仓库 `~/Desktop/agent/aster-infra/`（独立 git，模式对齐 aster-mcp）；本设计文档归主仓库 `docs/design/`。

## 目标

把三个「给智能体打工」的基础能力合成**一个可独立部署的 Spring Boot 服务**（端口 8125，名字 aster-infra），三个 REST 模块共用一套护栏、审计与运维：

1. **`/sandbox`**：容器化代码/命令执行沙箱。主服务 `TerminalOperationTool` 的执行后端从「本机 ProcessBuilder + 字符串策略」迁入「一次性 Docker 容器」，补掉 ROADMAP「将终端执行迁入独立 OS 沙箱」待做项；同时给超级智能体腾出 code interpreter 能力的地基。
2. **`/egress`**：统一出网网关（搜索/抓取/下载）。收敛目前散在三处的出网护栏（主服务 `UrlAccessPolicy`、aster-mcp `core/UrlGuard`、browser-mcp 内嵌 DNS 校验），补掉 ROADMAP「DNS rebinding 与统一 egress 策略」「搜索/抓取的重试、缓存、限流与可观测」待做项；响应自带 `title/url/fetchedAt` 引用元数据，为「联网结果作为用户可见引用」提供数据源。免费搜索 SearXNG 藏在本模块背后。
3. **`/llm`**（第三期，先留模块位）：OpenAI 兼容模型网关，统一 key 池、回退与用量记账。

监控用 Uptime Kuma 独立容器（不并入本服务——监控必须独立于被监控对象）；缓存第一期用进程内 Caffeine，不引入 Redis。

## 非目标

- 不做业务编排、不碰会话/知识库逻辑——那是主服务（8123）的职责，aster-infra 只提供无业务语义的基础设施。
- 第一期不做 OAuth、多租户、面板 UI；鉴权为单一 Bearer Token + 内网前提。
- **不修改、不复制主仓库 `application-local.yml` 的值**；沿用 aster-mcp 已验证的 `spring.config.import` 只读引用模式做密钥单一来源。
- 不替换 image/document/browser 三个既有 MCP 服务；本期也不把 browser-mcp 改接到 /egress（第三期再议）。
- /sandbox 第一期容器默认断网，不做 pip/npm 联网装包（需要新依赖就烧进镜像）。

## 当前状态（源码核实，2026-10-27）

- 主服务 `capability/tools/TerminalOperationTool`：`ProcessBuilder(sh -c)` 直接跑在宿主机，约束全靠 `ToolGuardPolicy.terminalViolation` 字符串策略（命令白名单 + shell 元字符 + 越权路径），30s 超时、8k 输出截断；含 Windows `cmd.exe /c` 分支。
- 主服务 `WebSearchTool` 直连 searchapi.io / tavily（key 来自 application-local.yml）；`WebScrapingTool`、`ResourceDownloadTool` 各自实现抓取/下载；`UrlAccessPolicy`、`ToolSandbox` 为宿主侧护栏。
- aster-mcp（8124）`core/`：`UrlGuard`（含 fake-IP DNS 代理环境下放行 ULA 的特判，见其会话摘要踩坑记录 4）、`ToolAudit`（JSONL）、`OutputLimiter`、`HtmlToMarkdown`；工程模式：`scripts/{build,run,launchctl,smoke-test,verify-sse}.sh` + `spring.config.import: optional:file:${主仓库 application-local.yml}`。
- browser-mcp（8129）内嵌自己的 DNS/allowlist 校验。
- 端口占用：8123 主服务、8124 aster-mcp、8127 image-mcp、8128 document-mcp、8129 browser-mcp、3000 前端、5432 PG、9000/9001 MinIO。**8125 空闲**。
- 教训（aster-mcp 会话摘要遗留 3）：SSE 端点无鉴权时不敢自动接主库凭据。aster-infra 拥有「执行任意代码」这种高危能力，**token 鉴权第一天就要有**，不重蹈覆辙。

## 总体架构

```text
                        ┌────────────────────────────────────────────┐
 主服务(8123) ──────────┤                                            │
 （terminal/web 工具    │        aster-infra (:8125)                 │
   后端可切换指向）      │   ┌──────────┬──────────┬──────────┐       │
                        │   │ /sandbox │ /egress  │ /llm(期3) │       │
 aster-mcp(8124) ──────┤   │ 一次性   │ URL安检   │ OpenAI   │       │
 （新增 sandbox 工具组，│   │ Docker   │ 缓存/限流 │ 兼容反代  │       │
   web 组改走 /egress） │   └────┬─────┴────┬─────┴──────────┘       │
                        └────────┼──────────┼────────────────────────┘
                                 │          │
                     Docker 容器池(断网)  SearXNG 容器(:8888,仅本机)
                                          Uptime Kuma 容器(:3001) 盯全部服务
```

- 技术栈：Spring Boot 3.4.x，**纯 REST，不依赖 Spring AI**（避免与主服务 1.0.0 / aster-mcp 1.1.8 的版本耦合，aster-mcp 踩坑记录 1）。JSON 序列化用 Jackson；HTTP 出网用 **Apache HttpClient 5**（`/egress` 需要其自定义 `DnsResolver` 做解析固定，JDK HttpClient 无法在保持 TLS 证书校验的同时按 IP 连接，见护栏节）；可观测性用 Micrometer 暴露 `/api/metrics`（Prometheus 文本格式），沙箱执行数/耗时、egress 请求数/拦截数为核心指标——对齐 ROADMAP「统一指标」。
- 新仓库 `aster-infra/`：Maven 单模块 `infra-server`；包结构 `com.zwx.asterinfra.{sandbox,egress,llm,common}`。
- 调用方只感知「一个 base-url + 一个 token」；三模块独立包与 REST 前缀，将来哪个长大可单独拆出（合并不焊死）。

## 模块契约

### /sandbox

```text
POST /api/sandbox/exec
  {"lang":"python|node|shell", "code":"...", "timeoutMs": 30000, "files":[{"name":"a.py","content":"..."}]}
→ 200 {"exitCode":0, "stdout":"...", "stderr":"...", "durationMs":1234,
       "artifacts":[{"name":"out.csv","size":1024,"downloadPath":"/api/sandbox/artifact/<id>"}]}
→ 401/403/409(并发满)/422(参数越界) 错误结构 {"error":"..."}
```

- 执行模型：**一次性容器**（`docker run --rm`），跑完即销毁，不做常驻池（macOS 上单次冷启 1~2s 可接受；池化留作优化）。容器操作通过 `docker` CLI 子进程封装，不引 docker-java；**docker 可执行路径显式配置**（`asterinfra.sandbox.docker-bin`，默认 `/usr/local/bin/docker`，本机实测为 OrbStack）——launchctl 作业 PATH 极简，不允许依赖 PATH 查找。
- **孤儿容器治理**：所有容器打 `asterinfra.run=<id>` label；服务启动时与每 5 分钟按 label 清扫已退出/超龄容器（`docker ps -a --filter label=asterinfra.run`）。`--rm` 只覆盖正常路径：Java 侧崩溃后没人 `docker kill`、或 OrbStack/VM 重启，都会留下残留容器，清扫器是兜底。
- 硬限制（**容器参数级强制是真正的安全边界**；主服务侧 `ToolGuardPolicy` 字符串策略保留为第一道低成本预筛，纵深防御而非废弃）：`--network none`（默认断网）、`--memory 256m`、`--cpus 1`、`--pids-limit 64`、`--read-only` + 可写 tmpfs `/tmp` 与工作目录、超时强杀、单机并发容器上限（默认 2，超出 409）。
- 镜像：`python:3.12-slim`、`node:22-slim`，以及自建 `aster-sandbox-cli`（ubuntu-slim + 常用 CLI：coreutils/grep/find/jq/ripgrep 等），由仓库内 `docker/` 构建脚本统一烧制——迁移 `TerminalOperationTool` 的白名单命令后，命令集只增不减。
- 工作区：每次 exec 分配 `sandbox/workspaces/<runId>/` 临时目录挂载进容器；`files` 入参预写文件；结束后 artifacts 从该目录收集（上限：50 个/单文件 10MB），供 `GET /api/sandbox/artifact/<id>`（带 token）下载；目录 TTL 回收（默认 1h）。
- 主服务接入：`ToolFactory` 新增 `SandboxTerminalTool`，与现 `TerminalOperationTool` 同签名同描述；`app.sandbox.mode = local | infra`（默认 local 不变，切换即迁移，保留 local 作为显式降级开关）。Windows 分支随迁移自然消亡。
- aster-mcp 接入：新增 `tools/sandbox` 组（组级开关沿用其 ToolboxConfig 模式），工具 `sandbox_run(lang, code)`。

### /egress

```text
POST /api/egress/search
  {"query":"...", "count":10}
→ {"results":[{"title","url","snippet"}], "provider":"searxng", "fetchedAt":"..."}

POST /api/egress/fetch
  {"url":"https://...", "maxBytes": 200000}
→ {"url","finalUrl","title","contentMd","contentType","fetchedAt"}   // 抓取+正文抽取+转 Markdown

POST /api/egress/download
  {"url":"..."} → {"artifactPath":"/api/egress/artifact/<id>","size","contentType"}  // 落地到网关产物区
```

- **护栏（合并三处实现为一处）**：`EgressGuard` —— scheme 白名单（http/https）、私网/环回/链路本地地址拒绝、**DNS rebinding 防护**：先解析并逐一校验目标域名的全部 IP，校验通过的 IP 经 Apache HttpClient 5 自定义 `DnsResolver` **在连接层固定**（连接用已校验 IP，TLS 证书与 SNI 仍按原域名），从机制上消除 guard-then-fetch 的二次解析竞态（JDK HttpClient 做不到这一点：按 IP 直连会挂证书校验，关校验是全局系统属性，且两次解析存在 TOCTOU 窗口）；重定向逐跳复检、响应大小上限；继承 aster-mcp 对本机 fake-IP DNS 代理环境（公网域名解析为 ULA）的放行特判，并把这条特判升格为网关显式配置项（`asterinfra.egress.allow-ula-resolve`）。
- **搜索 provider 顺序**：`searxng`（`http://127.0.0.1:8888/search?format=json`，免费）→ `tavily`/`searchapi`（key 沿密钥单一来源机制读取）→ 显式 `SEARCH_UNAVAILABLE`。provider 与顺序可配。**SearXNG 须挂载自定义 settings.yml 开启 `search.formats` 含 `json`**（默认不开，API 直接 403）。
- 抓取正文抽取：jsoup + 移植 aster-mcp `HtmlToMarkdown`（同源代码复制，不跨仓库依赖）。**内容边界**：仅处理 text/html 与纯文本；PDF/Office 等二进制文档不进网关，沿用 document-mcp 既有链路；JS 动态渲染页面明确归 browser-mcp，网关只做静态抓取。
- 重试：幂等 GET 类请求失败重试 2 次、指数退避；限流：每 token 简单令牌桶（搜索 10/min、抓取 30/min 起步，可配）；缓存：Caffeine 进程内，`fetch` 按 `url+maxBytes` 缓存（TTL 默认 10min），`search` 不缓存。
- 审计：JSONL 格式与 aster-mcp `ToolAudit` 对齐（时间/调用方 token 指纹/动作/目标 URL/结果摘要/耗时），落 `logs/egress-audit.jsonl`，按 10MB 滚动保留最近 5 个。
- 引用元数据：`title/url/fetchedAt` 即「联网引用」待做项所需三要素，主服务接入后直接透传给前端引用组件。
- 接入：主服务 `WebSearchTool`/`WebScrapingTool`/`ResourceDownloadTool` 增加可选网关后端（`app.egress.mode = direct | infra`，默认 direct 行为不变）；aster-mcp `tools/web` 组三工具改走网关（其 `UrlGuard` 保留为纵深防御）。

### /llm（第三期，本期只留模块位与 `/api/llm/health` 桩）

- OpenAI 兼容 `/v1/chat/completions` 反代 DashScope 等 provider；key 池轮转、失败换 key、按 key 限流、用量记账（输出与主服务 token 计量字段对齐）。
- 依赖前置：主服务模型配置表与网关的职责边界（配置权威在主服务还是网关）需在第三期开工前单独定，本期不实施。

## 安全与鉴权

- 所有 `/api/*`（除 `/api/health`）要求 `Authorization: Bearer <ASTER_INFRA_TOKEN>`；token 来源沿用密钥单一来源机制：`asterinfra.api-token` 键优先，回退主仓库 application-local.yml 中的同名新增键（**由用户自行增量添加，本仓库不含其值**）。日志只记 token 指纹（前 4 位 + 长度）。
- 沙箱即高危：token 错误率连续超阈值（默认 20 次/分钟）触发临时封禁；`/sandbox` 与 `/egress` 审计分文件落盘。
- 容器逃逸面控制：不挂载 docker.sock 之外任何宿主敏感路径；工作目录与产物目录独立于服务自身文件；镜像由本仓库脚本烧制、不拉未知第三方镜像。

## 配置

`application.yml`（全量默认值，见仓库）关键键：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | 8125 | |
| `asterinfra.api-token` | （空=拒绝启动 sandbox 模块） | 高危模块无 token 不放行 |
| `asterinfra.sandbox.enabled / max-concurrent / timeout-cap-ms` | true / 2 / 60000 | |
| `asterinfra.sandbox.docker-bin` | /usr/local/bin/docker | 显式路径，不依赖 launchctl 极简 PATH |
| `asterinfra.egress.search-providers` | searxng,tavily,searchapi | 顺序即回退链 |
| `asterinfra.egress.searxng-base-url` | http://127.0.0.1:8888 | |
| `asterinfra.egress.allow-ula-resolve / rate-limit.* / cache-ttl` | true / 见上文 / 10m | |
| `spring.config.import` | `optional:file:${ASTER_MAIN_LOCAL_YML:~/Desktop/agent/yu-ai-agent/src/main/resources/application-local.yml}` | 密钥单一来源；launchctl/run/IDE 三种启动方式天然生效（aster-mcp 踩坑记录） |

**调用方侧配置（此前遗漏，一并定义）**——token 单键三处复用：新增键写入主仓库 local yml 的 `asterinfra:` 段后，主服务直读、aster-infra 与 aster-mcp 经 `spring.config.import` 同源读取：

- 主服务：`app.sandbox.mode|base-url|token` 与 `app.egress.mode|base-url|token`（token 值引用 `${asterinfra.api-token:}`）；**主服务侧 HTTP 读超时须大于 sandbox 上限**（默认 70s > 60s cap），否则沙箱未跑完调用方先断连。
- aster-mcp：env `ASTER_INFRA_BASE_URL` / `ASTER_INFRA_TOKEN`。
- import 连带影响与防护：主仓库 local yml 现有顶层键为 `app`/`search-api`/`spring`（已核实），`spring:` 段（含 datasource）会随 import 进入 infra 上下文——依赖中不含 JDBC 驱动即不会触发自动装配，无实际接线；为防未来 local yml 新增 `server:` 等冲突键把端口带歪（aster-mcp 的 run.sh 目前也没固定端口），**launch 脚本显式 `--server.port=8125`**。

## 部署与运维

- 独立仓库 `~/Desktop/agent/aster-infra/`，脚本复刻 aster-mcp 模式：`scripts/build.sh`（`mvn package`）、`scripts/run.sh`、`scripts/launchctl.sh install|restart|stop`（label `com.zwx.aster-infra`，日志 `/tmp/aster-infra.log`；注意 launchctl 对 `~/Desktop` 下脚本 `Operation not permitted` 的坑，作业体内直接 `exec java -jar`，并显式 `--server.port=8125`）、`scripts/smoke-test.sh`（token 鉴权、sandbox 三语言、egress 安检用例：私网拒绝/rebinding 拒绝/正常抓取/searxng 搜索）。
- 侧边容器：`docker-compose.sidecars.yml`——`searxng`（127.0.0.1:8888，仅绑定本机回环，**挂载自定义 settings.yml 开启 JSON 输出格式**）+ `uptime-kuma`（127.0.0.1:3001，首次启动需手动建管理员账号并配置监控项）。两端口已核实空闲。Uptime Kuma 监控项：8123/8124/8125/8127/8128 `/health`、5432、9000；通知走 webhook（用户自配）。
- `GET /api/health` → `ok` + 各模块自检（docker 可达（按 docker-bin 探测）、searxng 可达、镜像存在、token 已配置）。

## 分期实施

1. **期 1（核心）**：`/sandbox` 全量 + `/egress` 的 search/fetch（含 EgressGuard 单测：继承 aster-mcp UrlGuard 用例 + rebinding 用例；smoke-test 全绿）；主服务 `SandboxTerminalTool` 以 `local|infra` 开关并切换验证。
2. **期 2**：aster-mcp web 组与主服务 WebSearch/WebScraping/Download 切网关；download 代理；Uptime Kuma 上线；`/egress` 限流与缓存调参。
3. **期 3**：`/llm` 模型网关；browser-mcp 接入 /egress 评估；sandbox 容器池化与产物对接 MinIO。

## 风险与取舍

- **单进程耦合**：三模块同进程，一模块故障互相影响——以「线程池隔离 + 每模块独立 health 子项 + 可独立 disable」缓解；模块边界清晰，拆分成本已压到最低。
- **macOS 容器运行时依赖**：本机实测为 **OrbStack**（docker context=orbstack，docker 在 /usr/local/bin/docker）。OrbStack 未运行时 `/sandbox` 显式报错拒绝执行（安全优先，不静默降级回宿主机执行）；主服务 `app.sandbox.mode=local` 是人工显式选择的逃生门。挂载工作区在 macOS 上走虚拟化文件系统，大量小文件场景 IO 偏慢——已用 artifacts 上限（50 个/10MB）与临时目录约束收口。
- **沙箱断网**：跑不了需要联网的代码（pip install 等），换来的是不给沙箱任何出网面；需要联网依赖时烧镜像，这条边界写进工具描述让模型知悉。
- **基础镜像供应链**：`python:3.12-slim`、`node:22-slim` 为第三方基础镜像，构建脚本按 digest 固定（`@sha256:...`）并记录于仓库，不漂移、不拉未知镜像。
- **searxng 被上游引擎风控**：数据中心 IP 可能被限流——回退链已兜底（tavily/searchapi），searxng 挂掉不致搜索不可用。
- **护栏合并的回归风险**：主服务/aster-mcp 现有防护不能变弱——切换前两边各自保留原护栏做纵深防御（先叠加、后收敛），smoke-test 覆盖安检用例。

## 验收口径

- `mvn test` 全绿（EgressGuard、沙箱限制、鉴权、限流）；
- `scripts/smoke-test.sh` 全绿：无 token 401、私网/rebinding 拦截、python/node/shell 三语言执行、断网验证（容器内 python socket 外连超时——slim 镜像无 curl）、孤儿容器清扫（预置带 label 的假容器被启动清扫回收）、searxng→tavily 回退、fetch 引用元数据完整；
- `/api/metrics` 可抓取 sandbox/egress 基础计数；
- 主服务以 `mode=infra` 跑通一次含终端命令的超级智能体任务，行为与 local 模式一致且 audit/trace 正常。
