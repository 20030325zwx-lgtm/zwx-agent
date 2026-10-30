# AI 创作 P1 运维与故障排查手册

日期：2026-10-30。范围：C15 产物（配置、观测、开关、存储与恢复）。本文件不包含任何密钥。

## 1. 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `creative.enabled` | `true` | 模块总开关（当前仅用于文档标记，接口仍以身份为准） |
| `creative.submissions-enabled` | `true` | 关闭后新任务返回 503 `SUBMISSIONS_DISABLED`，已有任务继续查询/入库 |
| `creative.scheduler.enabled` | `true`（可用 `CREATIVE_SCHEDULER_ENABLED` 覆盖） | 媒体任务调度循环；测试通过 `src/test/resources/application.properties` 关闭，保持用例确定性 |
| `creative.scheduler.user-max-inflight` | `2` | 用户同时提交上限（全局/供应商上限存于 `media_provider_quota`） |
| `creative.scheduler.interval-ms` / `batch-size` / `lease-seconds` | `1000` / `10` / `60` | 可用 `CREATIVE_SCHEDULER_INTERVAL_MS` 等环境变量调整 |
| `CREATIVE_SCHEDULER_ENABLED` | `true` | 关闭后新任务不再被认领（用于停止新提交后的排空演练） |
| `creative.storage.backend` | `oss` | `oss` 或 `local`；配置为 oss 但缺少凭据时自动回退本地并告警 |
| `creative.storage.local-dir` | `~/.aster/creative-media` | 本地媒体根目录（桌面端默认） |
| `creative.download.allow-local` | `false` | 仅桌面/隔离测试可开启；云端必须关闭以防 SSRF |
| `creative.download.max-bytes` | `20971520` | 产物下载上限，流式读取中即中断 |
| `app.workflow.media-resume.interval-ms` | `5000` | 工作流媒体等待恢复轮询间隔 |
| `creative.scheduler.recovery-interval-ms` | `30000` | 周期恢复间隔：接管启动时租约未过期的 SUBMITTING 等任务（2026-10-31 R1 修复后普通 tick 不再认领 SUBMITTING） |
| `creative.scheduler.sync-observation-seconds` | `600` | 同步适配器（openai-images）提交不明的观察窗口；到期仍无结果如实转 FAILED 并提示费用可能已产生 |
| `creative.adapters.openai-images.timeout-seconds` | `240` | OpenAI images 兼容（中转站）同步提交 HTTP 超时，同时是提交前租约延长窗口 |

配置文件：`src/main/resources/application.yml`；本地连接仍由 Git 忽略的 `application-local.yml` 提供，禁止修改或输出其中内容。

## 2. 就绪检查

`GET /api/ai/creative/health`（需登录）返回：

- `submissionsEnabled` / `schedulerEnabled`：开关是否允许新提交、后台是否在跑。
- `storageBackend`：实际生效的 `OSS` 或 `LOCAL`。
- `imageModelBound`：当前用户是否解析到「图片生成」绑定（不会回退对话模型）。
- `database`：数据库可查询。
- `jobs.active` / `jobs.attentionRequired` / `jobs.ingestFailed`：本人非终态、需关注、入库失败数量。

不返回密钥、对象键、签名 URL 或模型原文。

## 3. 指标

Micrometer 计数器（Prometheus 通过管理端口 8124 抓取）：

- `creative_jobs_submitted_total`：成功提交给供应商的次数。
- `creative_jobs_succeeded_total`：任务完成并入库。
- `creative_jobs_failed_total`：确认失败（含供应商拒绝与查询失败）。
- `creative_jobs_ingest_failed_total`：图片已生成但入库失败（可重试，不重新生成）。
- `creative_jobs_uncertain_total`：提交结果不明进入 RECONCILING；这类任务绝不自动重发。

## 4. 常见故障

| 现象 | 定位 | 处理 |
| --- | --- | --- |
| 提交返回 422 `UNSUPPORTED_PARAMETER` | 参数不在模型 schema（size/watermark）或提示词 >500 字 | 调整参数；不要静默透传未知参数 |
| 提交返回 429 | 排队数达到用户上限 | 等待现有任务完成；不会创建半成品 |
| 提交返回 503 `SUBMISSIONS_DISABLED` | 已关闭新提交 | 打开 `creative.submissions-enabled` 后再试 |
| 任务长期 RECONCILING | 提交网络结果不明 | 保持不重发；核对供应商任务列表；确认风险后再手动重试并勾选可能重复计费 |
| 任务 INGEST_FAILED | 对象存储写失败或结果 URL 过期 | 点「重新保存」（resume-ingest），只重新下载；不要重新生成 |
| 任务 attentionRequired | 查询多次仍未知或调度异常 | 查看 `blocked_reason`（`SUBMISSION_UNCERTAIN` / `SCHEDULER_ERROR`） |
| 删除供应商被拒 | 仍有在途媒体任务依赖其密钥/地址 | 等任务终态后再删除 |
| 工作流长期 WAITING_EXTERNAL | 媒体任务未终态 | 查看 `workflow_node_execution` 与对应 `media_job`；恢复器每 5 秒检查 |
| 桌面端无 OSS 仍要生图 | 未配置 OSS 凭据 | 已自动回退本地目录；确认 `creative.storage.local-dir` 可写 |

## 5. 停止新提交与收尾

1. 将 `creative.submissions-enabled=false` 并重启（或等下一批配置刷新）。
2. 观察 `creative_jobs_*` 与 `jobs.active` 归零；`jobs.ingestFailed` 需要人工「重新保存」。
3. 确认无 `RECONCILING` 后再备份数据库与媒体目录。
4. 恢复时先恢复数据库与媒体对象，再打开提交开关；已存在 taskId 的任务会继续查询，不会重新提交。

## 6. 日志与脱敏

- 以 `runId` / `jobId` / `requestId` 定位问题；供应商密钥只在内存中解密使用，不写日志。
- 错误摘要截断到 400 字符；不记录完整响应体和签名 URL 参数。
- 提交前检查：`git diff` 中不应出现 `application-local.yml`、`AK`/`SK`、签名参数。

## 7. 本地验证命令

```sh
mvn -B -ntp compile
mvn -B -ntp test -Dtest='CreativeMigrationTest,DashScopeImageAdapterTest,SafeMediaDownloaderTest,MediaJobStatusTest,ParameterSchemaTest'
cd frontend && npm run build
```

真实模型调用（A18）需要单独授权并记录模型、次数与费用；Mock 通过不等于真实模型验收通过。
