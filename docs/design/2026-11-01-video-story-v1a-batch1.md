# 视频生成第一批（V1-A）实施记录：视频底座与独立视频

日期：2026-11-01。基线 `e3caabd` + 本批改动。关联：[产品与架构](2026-11-01-video-story-product-architecture.md)、[执行计划](2026-11-01-video-story-implementation-plan.md)、[验收标准](2026-11-01-video-story-acceptance.md)。

本批覆盖任务卡 V00（协议核实）与 V1-A（文生视频 + 图生视频 + 流式/播放/下载 + 长任务复用 + 工作流视频节点 + 创作台快速生成入口）。剧情生产（S01/V1-B）与成片（V1-C）不在本批。

## V00：中转站视频协议核实（含真实调用验证 2026-11-01）

- **来源与日期**：New API 官方文档 doc.newapi.pro（OpenAI Sora 兼容视频接口页，2026-11-01 抓取）；KKAI 中转站（api.kkrich.ltd）首页确认其为 New API 系统；**最终以真实调用为准**（用户授权，2026-11-01）。
- **实测协议**（与 Sora 官方文档的差异已修入适配器）：
  - 提交 `POST {base}/videos` **JSON body** `{"model","prompt","seconds"}`（multipart 被中转站 sora 插件明确拒绝：上游为 Seedance，要求 JSON + 参考 URL）→ 200 `{"id","status":"queued"}`。
  - 轮询 `GET {base}/videos/{id}`：`queued` → **`in_progress`** → **`completed`**（非 succeeded），带 `progress`；成功响应字段为 **`video_url`**（上游 OSS 直链，可直接下载），另有 `/content` 兜底（200 video/mp4）。
  - 错误结构两套并存：网关 `{"code","message"}`（如 `plugin_request_invalid`）与上游 OpenAI 风格 `{"error":{code,message}}`，适配器双解析。
- **实测模型**：`sd_2.0_special_720p` 出片成功（5 秒片约 5 分钟，计费 1 次探测 + 1 次全链路）；`sd_2.5_special_1080p` 上游报 `invalid_model`（渠道侧问题，模型已保留启用但绑定切到 720p）；`sd-mini_special_720p` 无可用渠道。分组名「Seedance 视频 (distributor)」。
- **TODO（未验证项）**：图生视频参考 URL 传输的字段名（上游要求 JSON + 参考 URL，不接受文件上传——本版如实拒绝 `IMAGE_TO_VIDEO_PENDING`，不猜测计费）；`size` 字段（不发送）；音频能力；`_with_video_ref` 变体语义；上游取消（无协议，UNSUPPORTED）。

## 后端改动

| 模块 | 改动 |
| --- | --- |
| `platform/settings/ModelCatalogService` | 新增 `video_generation` 用途（PURPOSES/CAPABILITIES）与 `resolveVideoBinding`（用户>系统，不回退 chat） |
| `creative/domain/ParameterSchema` | 新操作常量 `text_to_video`/`image_to_video` + `openAiVideo()` schema（seconds/size 自由，UNVERIFIED） |
| `creative/service/MediaModelResolver` | resolve 按 operation 分流视频绑定；ensureProfile 增加 openai-compatible→`openai-video` 映射（只创建不覆盖，R07 模式） |
| `creative/provider/OpenAiVideoMediaProviderAdapter`（新） | 异步适配器：multipart 提交→accepted(taskId)；query 状态映射（succeeded 优先响应 url，缺省回退 /content 端点，404 如实 FAILED）；错误分类与 images 适配器同款（429 throttled / 401 403 AUTH / 4xx REJECTED / 5xx UNKNOWN）；cancel UNSUPPORTED |
| `creative/provider/SafeMediaDownloader` | 新增带 Bearer 的下载重载；令牌不跟随跨域重定向 |
| `creative/service/MediaJobService` | normalizeOperation 放行视频操作；kind×operation 矩阵（IMAGE→图像操作；**VIDEO→视频操作+关键帧图像操作**；NOVEL/COMIC 拒绝）；参考图规则（image_to_video 恰 1 张 IMAGE 素材；其余操作参考必须 IMAGE）；参考素材媒体类型校验 |
| `creative/job/MediaJobScheduler` | 视频下载预算 `creative.video.download-max-bytes`（默认 200MiB）；objectKey 扩展名 .mp4；转存带 Bearer（openai-video 适配器时）；`isVideoOperation` 公共判定 |
| `creative/asset/StorageAssetIngestService` | 入库 media_type 按 operation 映射 VIDEO；VideoProbe 尽力探测时长/画幅写 metadata 与 duration_ms；视频跳过图片缩略图 |
| `creative/video/VideoProbe`（新） | 纯 Java MP4（ISO BMFF）box 解析：mvhd 时长（v0/v1）、tkhd 画幅；尽力而为不阻断 READY；无 ffmpeg 依赖（受控 ffprobe 留给 R02） |
| `creative/asset/CreativeAssetService` | 新增 `uploadVideo`（魔数判定 MP4/MOV/WebM，预算 `creative.video.upload-max-bytes` 默认 100MiB，探测后 READY）；`playbackMeta`/`openStream` 支持流式；AssetView 增加 durationMs |
| `web/controller/CreativeAssetController` | 上传按魔数分流图片/视频；`/content` 对视频支持 Range（206/416，StreamingResponseBody，16KB 分块）；新 `GET /{id}/playback-url` 签发短时效播放签名 |
| `platform/security/PlaybackSignatureService`（新） | 播放签名：自描述令牌 `expires.tenant.user.asset.hmac`（HMAC-SHA256 复用 JWT 密钥），绑定素材+用户+过期 |
| `platform/security/JwtAuthFilter` | 第三通道：`?playback=` 签名校验（GET + 精确 content 路径 + 素材匹配）仅为该请求装配素材归属者身份，不产生通用身份 |
| `platform/workflow/WorkflowService` | 节点 `media_video_generate`（必填 prompt；imageAssetIds 提供时走图生视频；seconds/size 可选）；WAITING_EXTERNAL 桥接复用 |
| `platform/workflow/WorkflowMediaResumeScheduler` | 输出 mediaType 按产物资产实际 media_type 返回（VIDEO/IMAGE） |
| `creative/domain/CreativeKind` | `enabled()` 放行 VIDEO |

**数据库：零迁移**（`model_binding.purpose`/`media_job.operation` 无 CHECK；`creative_asset.media_type` 已含 VIDEO、`duration_ms` 已预留）。

**配置（application.yml，默认值兜底，不动 application-local.yml）**：multipart 10MB→100MB（图片仍由服务层 20MiB 兜底）；`creative.video.upload-max-bytes`(100MiB)/`download-max-bytes`(200MiB)/`creative.playback-ttl-seconds`(3600)。

## 前端改动

- `CreativeHome.vue`：视频创作 chip 与新建项目 VIDEO 选项放行。
- `CreativeProject.vue`：VIDEO 项目 studio 分支——生成方式（文生/图生视频）、首帧素材（AssetParamField mediaType=IMAGE）、时长/画幅自由输入；产物视频用签名 URL `<video>` 播放与下载；轮询复用（2.5s，视频不伪造进度）。
- `shared.js`：`useVideoUrls()`（签名 URL 缓存+过期重取）、`formatDuration`；状态文案泛化。
- `AssetPreview.vue`：mediaType=VIDEO 分支 `<video controls>` + 时长显示。
- `CreativeAssets.vue` / CreativeProject 素材 tab：视频卡片用签名 URL 原生首帧（不走图片缩略图链路）。
- `AssetParamField.vue`：新增 mediaType 属性（IMAGE 默认兼容旧用法/VIDEO）。
- `nodeCatalog.js`：`media_video_generate` 注册（新分组「视频生成」）；asset 输出/输入增加 media 子类型（视频不能接入只接受图片的节点；旧节点未声明视为 image，零迁移）。
- `ModelCatalogSettings.vue`：「视频生成」用途绑定行 + 视频生成能力标签。

## 取舍与已知边界

1. **协议 UNVERIFIED**：适配器严格按 Sora 兼容实现但未经真实调用；首次真实生成是验收动作（用户发起）。
2. 视频转存/上传仍是**整段内存**（≤200MiB）——受控流式落盘与逐块哈希留给后续批次（规划文档 §8 的有界流式完整版）。
3. 无 ffmpeg：VideoProbe 只解析 MP4 容器元数据，不抽帧（封面=播放器原生首帧）、不验证编解码完整性；WebM 上传无探测。
4. Range 实现手写（start-/start-end/-suffix），未处理 If-Range/多区间（浏览器 <video> 不需要）。
5. 播放签名密钥复用 JWT 密钥；JWT 轮换会使已签发播放 URL 失效（1 小时时效内影响可忽略）。
6. 下载逐跳校验沿用 SafeMediaDownloader：模型端点 allow-local 配置**不会**扩散为素材下载豁免。

## 验证

见 [验收证据](../test/results/2026-11-01-video-story-v1a.md)。单测：适配器 14 项（HTTP 分类）、VideoProbe 5 项（v0/v1/损坏容错）、kind×operation 矩阵 3 项；全量回归与 E2E 记录同文。
