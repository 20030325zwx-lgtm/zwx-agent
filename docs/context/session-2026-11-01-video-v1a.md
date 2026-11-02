# 视频生成第一批（V1-A 独立视频可用版）会话（2026-11-01）

## 改动内容

按 `docs/design/2026-11-01-video-story-{product-architecture,implementation-plan,acceptance}.md` 三份规划文档，实施第一批任务卡 V00–V04（完整清单见 `docs/design/2026-11-01-video-story-v1a-batch1.md`）：

- **V00 协议核实**：中转站（KKAI，New API 系统）视频协议按 OpenAI Sora 兼容 `/v1/videos`（multipart 提交→轮询→/content 下载，需 Bearer）实现，来源 doc.newapi.pro（2026-11-01 抓取）。**UNVERIFIED**：KKAI sd_* 是否真走该端点未经真实调用（未获计费授权）。
- **后端**：`video_generation` 用途/能力 + `resolveVideoBinding`；`ParameterSchema.openAiVideo()`（text_to_video/image_to_video）；新 `OpenAiVideoMediaProviderAdapter`（异步，错误分类与 images 适配器同款）；`SafeMediaDownloader` 带 Bearer 重载（令牌不跟随跨域重定向）；MediaJobService kind×operation 矩阵（VIDEO 项目=视频操作+关键帧图像操作，NOVEL/COMIC 拒绝）与参考图规则（image_to_video 恰 1 张 IMAGE）；调度器视频下载预算 200MiB + .mp4 objectKey + 带 Bearer 转存；`VideoProbe`（纯 Java MP4 mvhd/tkhd 解析）+ 入库 mediaType=VIDEO + duration_ms；视频上传（魔数分流，100MiB 预算）；`/content` Range 206/416 + `PlaybackSignatureService` 播放签名（HMAC 复用 JWT 密钥，绑定素材+用户+过期，JwtAuthFilter 第三通道仅对 GET content 路径装配身份）；工作流节点 `media_video_generate`（WAITING_EXTERNAL 复用），恢复器输出 mediaType 按素材实际值。
- **前端**：CreativeHome 视频 chip/新建项目放行；CreativeProject 视频表单（生成方式/首帧/时长/画幅）与视频产物签名 URL 播放；`useVideoUrls()`；AssetPreview `<video>` 分支；素材卡片视频原生首帧；AssetParamField mediaType 属性；nodeCatalog `media_video_generate`（「视频生成」分组）+ asset 媒体子类型约束（视频不能接只接受图片的节点，旧节点默认 image 零迁移）；模型设置「视频生成」绑定行。
- **配置**（application.yml）：multipart 10MB→100MB（图片仍由服务层 20MiB 兜底）；`creative.video.upload-max-bytes`(100MiB)/`download-max-bytes`(200MiB)/`creative.playback-ttl-seconds`(3600)。
- **数据库零迁移**（purpose/operation 无 CHECK；creative_asset 已含 VIDEO+duration_ms）。
- 用户配置（前一轮）：新建 provider 15「KKAI 视频中转站」（新 key 与旧生图 key 分组不同，旧 provider 14 未动），启用 sd_2.5_special_1080p(row 36)；本批将其绑定为用户的 video_generation 用途。

## 验证结果

- 新单测 22 项全绿（适配器 14 / VideoProbe 5 / 矩阵 3）；定向回归 22 类 128 项 0 失败；前后端构建通过；/api/health ok。
- E2E（无计费）：样片上传/探测（960×540/5055ms）、Range 206/416、播放签名防篡改、UI 播放、工作流节点库——全过。
- **真实供应商验证（用户授权，2026-11-01 下午）**：
  - 实测协议与 Sora 官方文档不同：**JSON 提交**（multipart 被拒，上游是 Seedance）、状态 `in_progress/completed`（非 succeeded）、结果字段 **`video_url`**、错误结构网关 `{"code","message"}` 与上游 `{"error":{...}}` 并存——适配器已全部修正并同步单测（14/14）。
  - **模型实测**：`sd_2.0_special_720p` 可用（出片 2 次：协议探测 + 后端全链路，各 5 秒约 5 分钟）；`sd_2.5_special_1080p` 上游 invalid_model（渠道故障，已把 video_generation 绑定切到 720p）；`sd-mini_*` 无渠道。
  - **后端全链路 SUCCEEDED**：创建任务→RUNNING（上游 task_id）→约 4.5 分钟→产物 2.9MB 入素材库（GENERATED/VIDEO）→UI 预览实际播放（1280×720，截图确认）。
  - 过程修复 3 个 bug（均有记录）：① Range 在 @RestController 下 ResponseEntity<?> 通配返回 500；② 播放签名 URL 双 /api 前缀；③ **`CreativeAssetRepository.insert` 缺 width/height/duration_ms 列**（bug-2026-11-01-creative-asset-insert-drops-dimensions.txt，历史上 GENERATED 素材尺寸一直是 null，视频把它暴露了）。
  - 图生视频：上游要求参考 URL 传输（字段名未验证）——适配器如实拒绝 IMAGE_TO_VIDEO_PENDING，UI 标注「即将开放」；size 参数不发送（未验证）。

2026-11-02 后续：New API 当前 `openai_video` 契约确认 JSON 字段为 `input_reference`；现已通过 OSS 预签名 URL 开放首帧图生视频，UI 不再标注「即将开放」。
- 桌面端两次 dist:mac；最终包含 JSON 适配器与 insert 修复（时间见 release/）。

## 遗留事项

- 图生视频参考 URL 传输验证（上游字段名未知，需再授权计费探测）；`size` 参数验证；`_with_video_ref` 变体与音频能力。
- `sd_2.5_special_1080p` 渠道故障（上游 invalid_model）可反馈中转站客服；当前绑定已切 `sd_2.0_special_720p`。
- 全量 `mvn test` 在本环境会挂起（某测试类 32 分钟无进度，已按仓库惯例改定向回归）——原因待查。
- target/classes 曾出现 232 个 macOS 复制副本 "* 2.class"（本次已清理；10-31 有同类先例）——构建异常时先查这个。
- 剧情生产 S01/V1-B（剧本/分镜/批次）与成片 V1-C 未动；视频受控流式落盘、WebM 探测、ffmpeg 抽帧/成片渲染属后续批次。
- 本批改动未提交（等待用户确认）。

## 环境注意事项

- 未读取/修改 application-local.yml；构建必须从仓库根执行 mvn（frontend 目录下无 POM）。
- 播放签名密钥复用 JWT 签名密钥；JWT 轮换会使已签发播放 URL 失效（1 小时时效内）。
- 测试样片在 /tmp/aster-video-sample/flower.mp4（MDN CC0）。
