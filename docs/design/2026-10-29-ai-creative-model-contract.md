# AI 创作：首个图片模型契约（C00 交付）

日期：2026-10-29。状态：C00 基线锁定，供 C01–C07 适配器实现使用。
配套：[总体设计](./2026-10-29-ai-creative-studio.md)｜[实施手册](./2026-10-29-ai-creative-implementation.md)｜[验收标准](../test/2026-10-29-ai-creative-acceptance.md)

## 1. 实施基线（接手核对结果）

- HEAD：`94a53e8`（修复图片搜索 MCP 的 Pexels 配置与响应处理，2026-10-29 20:57 +0800），与实施手册核对基线一致。
- 工作区差异（保留不清理）：`docs/design/README.md`、`docs/test/README.md` 修改；设计/实施/验收/会话四份文档未跟踪；`frontend/src/views/WorkflowEditor 2.vue`、`Workflows 2.vue` 为其他会话副本，不作为实现。
- 迁移：V1–V7 已存在，本模块新迁移从 V8 起，实施时再次确认。
- 已实现并复用（不重复开发）：`ModelCatalogService.PURPOSE_IMAGE`（`image_generation`）、`resolveImage(userId)`（用户绑定 > 系统绑定，未绑定返回 empty）；`WorkflowService.imageGenerate` 优先图片绑定，回退 chat + 节点 model 参数；前端「图片生成」用途行。
- 旧链路已知限制（C13/C14 处理，不在 C00 范围）：生图轮询用 `Thread.sleep` 占用工作流线程（120 秒截止）；任务状态仅 RUNNING/SUCCEEDED/FAILED。

## 2. 选定模型与验证状态

| 项 | 值 | 来源 |
| --- | --- | --- |
| 供应商 | 阿里云百炼 DashScope | 目录绑定 providerId=1「DashScope 官方」 |
| 主模型 | `wanx2.1-t2i-plus` | 目录模型条目 row 8，USER 绑定「图片生成」 |
| 备选模型 | `wanx2.1-t2i-turbo` | 目录模型条目 row 9（更快更省，可切换绑定） |
| 真实验证 | 2026-10-29 两个模型各成功生成 1 张（`usage.image_count=1`） | 本会话实测，记录于 docs/context/session-2026-10-29-ci-and-zhipu-provider-fix.md |
| Key | 已配置且可用（`****7806`），本文件不含密钥 | application-local.yml，未读出明文 |

## 3. 接口契约（来源：help.aliyun.com 官方文档，访问日期 2026-10-29）

文档出处：
- V1 接口页（wanx-v1）：`https://help.aliyun.com/zh/model-studio/text-to-image-api-reference`
- V2 接口页（wanx2.1 / wan2.x 系列）：`https://help.aliyun.com/zh/model-studio/text-to-image-v2-api-reference`

### 3.1 提交任务

| 项 | 契约 |
| --- | --- |
| 方法/路径 | `POST /api/v1/services/aigc/text2image/image-synthesis` |
| 域名 | 文档示例 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com`（北京）；当前代码用 `https://dashscope.aliyuncs.com` 且已实测可用。域名策略：沿用 `dashscope.aliyuncs.com` 为默认，是否必须 Workspace 域名列为未知 U1 |
| 必需头 | `Authorization: Bearer <key>`、`Content-Type: application/json`、`X-DashScope-Async: enable`（缺失报 "current user api does not support synchronous calls"） |
| `model` | `wanx2.1-t2i-plus` / `wanx2.1-t2i-turbo`（本契约）；同接口亦支持 wan2.x 系列 |
| `input.prompt` | 必选；wanx2.1 系列 ≤500 字符（设计 BR05 的 8000 为平台侧上限，提交前按模型更严限制校验） |
| `input.negative_prompt` | 可选 ≤500 字符；P1 不开放（BR07），适配器保留字段位 |
| `parameters.size` | 默认 1024*1024；宽高各 [512,1440]（wan2.2 及以下规则） |
| `parameters.n` | 1–4；P1 固定 1（BR01） |
| `parameters.prompt_extend` | bool 默认 true；可能触发内容审查错误，P1 显式传 false 以减少不确定分支（待 C07 实测确认） |
| `parameters.watermark` | bool 默认 false（右下角“AI生成”水印） |
| `parameters.seed` | [0, 2147483647]；P1 不开放（BR07） |
| 参考图 | wanx2.1 文生图接口无参考图字段；`ref_img/ref_strength/ref_mode` 仅 wanx-v1。换装/参考图属 `image_reference` 能力，另行适配（设计 7.1） |

### 3.2 查询任务

| 项 | 契约 |
| --- | --- |
| 方法/路径 | `GET /api/v1/tasks/{task_id}`，仅需 Authorization |
| 轮询间隔 | 官方建议约 10 秒（当前代码 3 秒，C07 改为退避 3s→15s+抖动） |
| 查询限流 | 查询接口 RPS 20（账号级） |

### 3.3 响应结构（脱敏样本，结构来自官方文档与 2026-10-29 实测）

提交成功：
```json
{"output":{"task_id":"<task_id>","task_status":"PENDING"},"request_id":"<request_id>"}
```

查询成功：
```json
{"output":{"task_id":"<task_id>","task_status":"SUCCEEDED",
  "results":[{"url":"https://<bucket>.<region>.aliyuncs.com/...?Expires=<ts>&Signature=<sig>"}],
  "task_metrics":{"TOTAL":1,"SUCCEEDED":1,"FAILED":0}},
 "usage":{"image_count":1},"request_id":"<request_id>"}
```

失败（任务级）：
```json
{"output":{"task_id":"<task_id>","task_status":"FAILED","code":"InvalidParameter",
  "message":"...size..."},"request_id":"<request_id>"}
```

常见错误码（文档列示）：`InvalidApiKey`、`InvalidParameter`、`InternalError.Timeout`、`IPInfringementSuspect`、`DataInspectionFailed`。

### 3.4 状态与语义映射

| 上游状态 | 含义 | 适配器分类（设计 7.2） |
| --- | --- | --- |
| PENDING / RUNNING | 已受理，生成中 | Accepted（RUNNING） |
| SUCCEEDED | 成功，results[].url 可取 | Completed（进入 FETCHING） |
| FAILED | 确认失败（含内容审查） | Rejected（终态，error_code 透传） |
| CANCELED | 已取消 | Rejected（CANCELLED 终态） |
| UNKNOWN | 上游状态不明 | Unknown（RECONCILING） |

HTTP 分类：提交 2xx 且有 task_id → Accepted；2xx 无 task_id → Unknown；`InvalidApiKey`/`InvalidParameter` 等 4xx → Rejected（Key 错误额外置 blocked_reason=PROVIDER_AUTH_FAILED）；429 → 按 Retry-After 退避重试查询；POST 5xx/读超时 → Unknown（不自动重发）。GET 查询失败仅重试查询。

### 3.5 取消、幂等、计费与保留期

| 项 | 契约 | 对本模块的影响 |
| --- | --- | --- |
| 取消 | SDK 注释：仅 PENDING 可取消；HTTP 取消端点未在文档给出 → 按“不支持取消”实现 | 适配器 cancel 返回 Unsupported；UI 不显示“已取消”（BR13） |
| 幂等 | 未提供客户端幂等键；仅返回 `request_id` 溯源 | 本地幂等（idempotency_key 唯一约束）为唯一防线，POST 5xx 一律 Unknown 不重发 |
| 计费 | 按成功张数计费（`usage.image_count`）；失败不收费；单价见控制台，文档未列 | `media_usage_record.actual_amount` 保存 NULL 并标记来源未知（BR17） |
| 结果保留 | task_id 与图像 URL 仅保留 24 小时 | FETCHING 必须及时下载转存；URL 过期→查询可能无法刷新结果，走 INGEST 语义不重生图 |
| 限流 | 提交限流未在 V2 页列明（V1 为 QPS 2、并发 1）；查询 RPS 20 | 配额默认 provider_inflight=4 偏保守，A10 实测后调整 |

## 4. 未知项与门禁影响（A01）

| ID | 未知项 | 影响 |
| --- | --- | --- |
| U1 | 是否必须使用 `{WorkspaceId}` 专属域名 | 不阻断 Mock；真实 adapter 前实测确认，当前默认域名已验证可用 |
| U2 | wanx2.1 提交接口限流数值 | 不阻断 Mock；配额先用保守默认，A10 压测后调 |
| U3 | HTTP 取消端点是否存在 | 阻断“真实取消”功能；不阻断底座（cancel=Unsupported） |
| U4 | 各模型精确单价 | 阻断费用展示；`actual_amount` 保持 NULL，不虚构 |
| U5 | `prompt_extend=false` 是否减少内容审查误伤 | C07 真实烟测时确认 |

## 5. 真实烟测前置条件（A18 执行清单）

1. DashScope Key 可用且计费开通（已满足）；记录授权费用范围。
2. C06 存储适配就绪（OSS 桶 `zwx-agent` 或桌面本地目录）。
3. 图片生成绑定存在（已满足：wanx2.1-t2i-plus，USER 绑定）。
4. C07 adapter 与任务链路就绪后，按“1 次调用”执行并记录：模型、脱敏 task_id、jobId、产物摘要、费用是否已知。
5. 烟测不进入普通 CI；样本（成功/失败）进入 Mock 用例集，标识符脱敏。

## 6. A01 核验结论

- 基线差异明确（第 1 节）；模型信息全部有出处（官方文档 URL + 访问日期 + 本会话实测记录）。
- 无假接口、无虚构价格：单价、限流、取消端点均如实标为未知/不支持。
- 未知项已按“阻断真实 adapter / 不阻断 Mock 底座”分类（第 4 节）。
- 结论：A01 PASS（证据＝本文件 + 提交记录）；C00 达成完成定义。
