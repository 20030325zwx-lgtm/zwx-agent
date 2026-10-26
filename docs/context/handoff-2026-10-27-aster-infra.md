# aster-infra 交接文档（2026-10-27，面向接手智能体）

> 本文是 aster-infra（Aster 基础设施服务）的**权威交接入口**，接手前通读一遍即可获得全部前置状态。
> 会话流水见 `session-2026-10-27-aster-infra.md`；设计全文见 `docs/design/2026-10-27-aster-infra.md`。
> 开始任何改动前，先按 §4 跑一遍验证，确认现状与你读到的描述一致；不一致以源码为准并更新本文。

## 0. 30 秒现状

- **aster-infra 期 1 已交付并全绿**：沙箱执行（/sandbox）+ 出网网关（/egress），独立仓库 `~/Desktop/agent/aster-infra/`，commit `d975807`。
- **当前部署方式：docker compose 常驻**（aster-infra + searxng 两容器，:8125/:8888 均仅绑 127.0.0.1）。launchctl 宿主模式**未安装**（与 compose 抢 8125 端口，二选一）。
- token 在 `aster-infra/.env`（gitignored，48 位 hex，本地生成）。**主仓库 `application-local.yml` 全程未动**，其中没有 `asterinfra.api-token` 键——需要宿主模式时由用户自行增量添加。
- 待做主线：期 2 = 主服务与 aster-mcp 接入本服务（§5 有精确到文件的指引）。

## 1. 服务版图与运行状态

| 端口 | 服务 | 部署方式 | 仓库/位置 | 说明 |
| --- | --- | --- | --- | --- |
| 8123 | aster 主服务（yu-ai-agent） | launchctl `com.zwx.yu-ai-agent.backend` | `~/Desktop/agent/yu-ai-agent` | 智能体大脑；本批未改代码 |
| 8124 | aster-mcp 工具箱 | launchctl `com.zwx.aster-agent-mcp` | `~/Desktop/agent/aster-mcp` | 20 工具 MCP（SSE+stdio）；本批未改 |
| **8125** | **aster-infra（本期交付）** | **docker compose** | `~/Desktop/agent/aster-infra` | /sandbox + /egress（/llm 第三期预留） |
| 8127/8128/8129 | image/document/browser-mcp | compose / profile | 主仓库 `services/` | 未动 |
| 8888 | searxng sidecar | compose（aster-infra 栈） | aster-infra `deploy/searxng/settings.yml` | 仅本机回环；JSON 格式已开启 |
| 3001 | uptime-kuma | compose `--profile ops`（未启动） | 同上 | 起了后需 :3001 手工建账号配监控项 |
| 3000/5432/9000 | 前端/PG/MinIO | 原有 | — | 未动 |

健康快照命令：`curl http://127.0.0.1:8125/api/health`（应返回 `ok`）；带 token 的 `GET /api/status` 报 `{"tokenConfigured":true,"sandbox":{"docker":true},"egress":{"searxng":true,...}}`。

## 2. 仓库地图

### aster-infra（`~/Desktop/agent/aster-infra/`）

```
├─ pom.xml                     Spring Boot 3.4.5 + Java 21；纯 REST 无 Spring AI；httpclient5/jsoup/caffeine/micrometer
├─ src/main/java/com/zwx/asterinfra/
│  ├─ InfraApplication.java    @EnableScheduling + @ConfigurationPropertiesScan
│  ├─ common/                  TokenAuthService(token 解析与指纹)、TokenAuthFilter(除 /api/health 全拦截，
│  │                           未配 token→503)、AuthRateGuard(错误 token 20/min 封 60s)、RateLimiter(滑窗)、
│  │                           AuditWriter(JSONL 10MB×5 滚动，防御性拷贝)、InfraMetrics(Prometheus)、
│  │                           HealthController(/api/health 公开 + /api/status)、EgressProbes(searxng 探针)、
│  │                           ApiExceptionHandler(ApiException/404/405/400/500 映射)
│  ├─ sandbox/                 SandboxProperties、SandboxService(核心：一次性容器 exec、docker CLI 封装、
│  │                           孤儿清扫 @PostConstruct+@Scheduled、dockerAlive 探针用 docker info)、
│  │                           SandboxController(exec + artifact 下载，runId 正则+path 归一化双重防穿越)
│  └─ egress/                  EgressProperties、EgressGuard(scheme/localhost/字面 IP 严格口径/域名逐 IP 校验)、
│                              GuardingDnsResolver(hc5 连接层固定已校验 IP，TLS/SNI 按原域名)、
│                              EgressClientFactory(guardedClient 带护栏 / directClient 仅限本机 searxng)、
│                              SearchProviders(searxng→tavily→searchapi 回退链 + Facade)、
│                              FetchService(限长/重试/Caffeine 缓存/HTML→MD)、HtmlToMarkdown(移植自 aster-mcp 并修复)
├─ src/main/resources/application.yml   全部默认配置；spring.config.import 只读引用主仓库 local yml（密钥单一来源）
├─ docker-compose.yml          aster-infra + searxng + uptime-kuma(ops profile)；顶级卷显式 name: 固定（踩坑 7.1）
├─ Dockerfile                  多阶段构建；COPY docker:28-cli 的 docker CLI（沙箱 sibling 执行用）
├─ deploy/searxng/settings.yml use_default_settings 最小覆盖；formats 必须含 json
├─ docker/cli-image/Dockerfile aster-sandbox-cli:latest（shell 沙箱镜像）
├─ scripts/                    build.sh / run.sh / launchctl.sh(install|restart|stop|status) /
│                              smoke-test.sh(17 项自测) / build-cli-image.sh
├─ docs/bugs/bug-2026-10-27-compose-volume-prefix-and-docker-probe.txt
└─ .env(.gitignored)           ASTER_INFRA_API_TOKEN=<48 hex>
```

测试：`src/test/java/...` 9 类 48 例（EgressGuard 全私网口径、DnsResolver、搜索回退、HtmlToMarkdown、审计轮转、限速、鉴权 MockMvc 含无 token 上下文、沙箱校验与 docker 命令旗标、artifact 穿越拦截）。

### 主仓库触点（`~/Desktop/agent/yu-ai-agent/`）

- `docs/design/2026-10-27-aster-infra.md`：设计全文 + 实施状态（已标注实现与设计的两处差异）。
- `docs/context/session-2026-10-27-aster-infra.md`：会话流水摘要。
- 上述两文件 + 本批之前遗留的工具循环防护改动**均未提交**（git status 可见，主仓库其余未提交改动属前一会话，与本批无关）。

## 3. 关键设计决策（为什么是这样，改前三思）

1. **纯 REST 不依赖 Spring AI**：主服务用 Spring AI 1.0.0、aster-mcp 用 1.1.8，版本互斥教训在案；aster-infra 不需要模型能力。
2. **一次性容器而非常驻池**：macOS（OrbStack）冷启 1~2s 可接受，换來零状态回收简单；池化是后续优化项。
3. **token 第一天就有**：沙箱=任意代码执行，aster-mcp「SSE 无鉴权不敢接主库凭据」教训的直接吸收；未配置 token 时受保护端点整体 503（绝不静默放开）。
4. **DNS rebinding 用 hc5 DnsResolver 连接层固定**：JDK HttpClient 做不到「按 IP 连接 + TLS 校验按域名」；guard-then-fetch 两次解析有 TOCTOU 竞态。改这里前先读 EgressGuardTest。
5. **沙箱默认断网**：不给任何出网面，需要依赖就烧镜像；这是有意取舍不是缺陷。
6. **主服务/aster-mcp 的护栏在期 2 接入后保留**（纵深防御），aster-infra 收敛的是「口径」，不是删调用方防护。
7. **缓存不引 Redis**：Caffeine 进程内够用；PG 兜底的场景留给真有需求时。

## 4. 验证手册（接手后先跑这个）

```sh
cd ~/Desktop/agent/aster-infra

# 1) 单测（不依赖 docker/网络）
mvn test                        # 期望：Tests run: 48, Failures: 0

# 2) 冒烟（对运行中的 8125；需服务已起）
TOKEN=$(grep ASTER_INFRA_API_TOKEN .env | cut -d= -f2)
ASTER_INFRA_TOKEN="$TOKEN" bash scripts/smoke-test.sh   # 期望：PASS=17 FAIL=0

# 3) 部署状态
docker compose ps               # aster-infra + searxng 两容器 Up
curl http://127.0.0.1:8125/api/health   # ok
```

改动后重建：`bash scripts/build.sh && docker compose up -d --build aster-infra`（容器模式）；
宿主模式 `bash scripts/launchctl.sh install` 前必须先 `docker compose down`（抢 8125）。

## 5. 期 2 待办（精确到文件的实施指引）

主线：把主服务和 aster-mcp 的执行/出网后端切到 aster-infra。**顺序敏感：先主服务沙箱（独立收益最大），再 aster-mcp，最后 browser-mcp 评估。**

### 5.1 主服务沙箱迁移（对应 ROADMAP「终端迁入独立 OS 沙箱」）

- 新增 `capability/tools/SandboxTerminalTool.java`：与 `TerminalOperationTool` 同签名同 `@Tool` 描述，内部把 `executeTerminalCommand` 翻译为 `POST /api/sandbox/exec {lang:"shell", code:<command>}`（HTTP 客户端读超时 **70s > cap 60s**）。
- `capability/tools/ToolFactory.java` + `TravelToolConfig.java`：按 `app.sandbox.mode = local|infra` 二选一装配，默认 `local` 行为不变。
- 配置键（application.yml + local yml）：`app.sandbox.mode/base-url/token`，token 值引用 `${asterinfra.api-token:}`。
- `ToolGuardPolicy.terminalViolation` 字符串策略**保留为第一道预筛**（纵深防御，不删）。
- 注意：shell 沙箱命令集 = aster-sandbox-cli 镜像内的工具（coreutils/grep/find/jq/ripgrep 等），与宿主白名单命令集对齐情况要在切换前对一遍，缺的补进 `docker/cli-image/Dockerfile` 重烧镜像。
- 验收：`mode=infra` 跑一次含终端命令的超级智能体任务，行为与 local 一致，trace/audit 正常；主仓库补 bug 记录与会话摘要。

### 5.2 aster-mcp 接入

- 新增 `tools/sandbox` 工具组（组级开关沿用 `ToolboxConfig` 模式），工具 `sandbox_run(lang, code)` → 调 8125。
- `tools/web` 三工具（WebFetchTool/WebExtractLinksTool 等）增加可选网关后端：env `ASTER_INFRA_BASE_URL` / `ASTER_INFRA_TOKEN`；其 `UrlGuard` 保留为纵深防御。
- aster-mcp 在独立仓库有自己的会话摘要体系（其仓库 docs/），改动后在那边也要记。

### 5.3 其他期 2 项

- `/egress` download 代理（`ResourceDownloadTool` 的远端后端）。
- uptime-kuma 监控项：`docker compose --profile ops up -d` → :3001 手工建账号 → 盯 8123/8124/8125/8127/8128 `/health`、5432、9000，webhook 通知用户自配。
- 基础镜像 digest 固定（`python:3.12-slim`/`node:22-slim` 目前按 tag）。

### 5.4 期 3（动工前需先定边界）

`/llm` 模型网关（OpenAI 兼容反代 + key 池）——先回答「模型配置权威在主服务模型配置表还是网关」；browser-mcp 接入 /egress 评估；沙箱容器池化与产物对接 MinIO。

## 6. 修改本服务时的硬约束

1. **主仓库 `application-local.yml` 禁改**（AGENTS.md 非协商条款）：aster-infra 只经 `spring.config.import` 只读引用；新增 token 键是用户动作。
2. **安全底线不可回退**：断网/资源限制/token 闸门/路径校验/字面 IP 严格口径，任何"临时放开"都必须走显式配置项 + 文档记录，不允许硬编码放宽。
3. 单测与冒烟是门禁：改 EgressGuard/SandboxService 必须同步补测试；冒烟 17 项语义不得弱化（历史上产物断言假阳性被修过一次）。
4. 三模块（sandbox/egress/llm）保持分包分前缀，合并不焊死——拆分预案写在设计文档。

## 7. 必读踩坑清单（都是真踩过的）

1. **compose 顶级卷名加项目前缀**：`asterinfra-workspaces` 实际是 `aster-infra_asterinfra-workspaces`；已用顶级 `name:` 固定，`SANDBOX_VOLUME_NAME` 与之必须严格一致，否则沙箱挂到隐式空卷、python/node 全挂（shell 因代码内联侥幸可用，极具迷惑性）。
2. **docker CLI 模板探针**：`docker version --format {{.ServerVersion}}` 会在 client 结构上执行报错；用 `docker info --format {{.ServerVersion}}`。
3. **searxng 默认不开 JSON**：`/search?format=json` 直接 403；`deploy/searxng/settings.yml` 的 `formats` 已加 json，别删。
4. **本机是 OrbStack + fake-IP DNS 代理环境**（公网域名解析为 fdfe:: ULA）：出网护栏有 `allow-ula-resolve` 显式配置（默认 true，仅域名解析路径放行，字面 IP 的 ULA 仍全拦）。
5. **launchctl 对 `~/Desktop` 下脚本 Operation not permitted**：launchctl 作业体内直接 `exec /usr/bin/java -jar`，并显式 `--server.port=8125`（防主仓库 local yml 未来新增 `server:` 键带歪端口）；docker 路径必须显式 `asterinfra.sandbox.docker-bin`（launchctl PATH 极简）。
6. **主仓库不能用 mvnw**（不可执行），aster-infra 直接用系统 `mvn` 3.8.6；Java 21。
7. **冒烟脚本注意 bash 3.2**（macOS 自带）：空数组展开、全角字符、`set -u` 都踩过，写法保持朴素。
8. **8125 端口两种部署模式互斥**；compose 的 `.env` 缺 `ASTER_INFRA_API_TOKEN` 会拒绝 up（有意设计）。

## 8. 仓库协议（本生态的固定约定）

- 每修一个 bug → 对应仓库 `docs/bugs/bug-YYYY-MM-DD-<主题>.txt`（纯文本，含验证结果）。
- 一批重要改动收尾 → 主仓库 `docs/context/session-YYYY-MM-DD-<主题>.md`（不删旧摘要，被取代的标注）。
- 设计文档 → 主仓库 `docs/design/YYYY-MM-DD-<主题>.md`；实现与设计出现差异时回头更新设计文档的实施状态标注。
- 主服务重启流程：`mvn -DskipTests package`（先 clean 更稳）→ `launchctl kickstart -k gui/$UID/com.zwx.yu-ai-agent.backend`。

## 9. git 状态（截至 2026-10-27）

- `aster-infra`：clean，commit `d975807`（期 1 全量）。
- `yu-ai-agent`：未提交 = `docs/design/2026-10-27-aster-infra.md`（新）+ `docs/context/session-2026-10-27-aster-infra.md`（新）+ 本文档（新）+ 前一会话遗留的工具循环防护代码改动（非本批，见 `session-2026-10-26-tool-loop-guard.md` §3 遗留 0）。
- `aster-mcp`：本批零改动。
