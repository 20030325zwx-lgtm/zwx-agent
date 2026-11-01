# 视频生成第一批（V1-A）验收证据

日期：2026-11-01。代码：本批未提交时为工作区改动（基线 e3caabd）。环境：本地 macOS，后端 `127.0.0.1:8123`（launchctl），前端 Vite `127.0.0.1:3000`，存储 OSS（application-local.yml，未读取/修改）。

## 用例与结果

| 编号 | 用例 | 类型 | 结果 |
| --- | --- | --- | --- |
| T01 | 视频适配器 HTTP 行为分类（201+id→ACCEPTED、401/403→AUTH、429+Retry-After→throttled、2xx 缺 id→UNKNOWN、4xx→REJECTED、5xx→UNKNOWN、multipart input_reference、双参考图拒绝、query 状态映射含 404 如实 FAILED） | Mock 单测 | ✅ 14/14（OpenAiVideoMediaProviderAdapterTest） |
| T02 | VideoProbe：v0/v1 mvhd 时长、tkhd 画幅、非 MP4/损坏数据安全返回不支持 | 单测（手构 MP4 box） | ✅ 5/5 |
| T03 | kind×operation 矩阵：IMAGE 仅图像操作；VIDEO 视频+关键帧图像操作；NOVEL/COMIC 全拒 | 纯函数单测 | ✅ 3/3 |
| T04 | 定向回归 22 个测试类（媒体任务/调度/解析器/工作流校验/迁移/素材/小说等） | 回归 | ✅ 128/128，0 失败 |
| T05 | `mvn -DskipTests package` + 前端 `npm run build` | 构建 | ✅ |
| T06 | 中转站模型绑定：PUT /settings/bindings/video_generation → provider 15 / sd_2.5_special_1080p，目录回读正确 | API | ✅ |
| T07 | VIDEO 项目创建（后端门禁放行 + 创作首页 chip「视频创作 · 已开放」+ 项目列表显示） | API + UI | ✅ |
| T08 | 视频上传（CC0 样片 flower.mp4 1.1MB，MDN）：201、mediaType=VIDEO、READY、VideoProbe 实测 960×540 / durationMs=5055（真实 MP4 解析正确） | API | ✅ |
| T09 | Range 播放：bytes=0-1023 → 206 + `Content-Range: bytes 0-1023/1128375`；bytes=500000- → 206；非法区间 → 416 + `bytes */size`；无 Range → 200 全量 | API | ✅ |
| T10 | 播放签名：playback-url 签发 → 无 Authorization GET 返回 200 video/mp4；篡改签名 → 401 | API | ✅ |
| T11 | UI 播放：项目素材库视频卡片（`<video>` 原生首帧）→ 预览弹窗 `<video controls>` 实际播放（currentTime 2.9s→5.06s，960×540，截图确认） | UI（内嵌浏览器） | ✅ |
| T12 | 创作台视频表单：生成方式（文生/图生）、时长、画幅、视频模型绑定展示（KKAI / sd_2.5_special_1080p）、生成按钮门禁 | UI | ✅ |
| T13 | 工作流节点库出现「视频生成」分组 + 「视频生成（创作）」节点 | UI | ✅ |
| T14 | 桌面端 dist:mac 重打包：`Aster-0.1.2-arm64.dmg`（2026-11-01 14:52）；已解包核对 jar（14:51 构建）含 OpenAiVideoMediaProviderAdapter/PlaybackSignatureService/VideoProbe，CreativeAssetController 为 playbackUrl 相对路径修复版，前端 asar 为含 API_BASE_URL 修复的最新构建 | 打包 | ✅ |

## 过程中修复的问题

1. Range 分块在 @RestController 下 `ResponseEntity<?>` 通配返回导致 StreamingResponseBody 处理器选不上（500）——改为直接写 HttpServletResponse。
2. 播放签名 URL 前端拼接：开发环境 API_BASE_URL 为跨端口绝对地址（Vite 无 /api 代理），后端改为返回不含 context-path 的控制器相对路径，前端统一拼 API_BASE_URL。

## 未测 / 未验证项（诚实记录）

- **真实供应商视频生成：已验证（用户授权，2026-11-01 补充）**。见下方「真实供应商验证」节。
- 图生视频（image_to_video）：上游要求 JSON + 参考 URL 传输，字段名未验证——适配器如实拒绝（IMAGE_TO_VIDEO_PENDING），待下一批开放。
- `size` 参数未发送未验证；音频能力；`_with_video_ref` 变体语义；上游取消（无协议）。
- 工作流视频节点端到端运行（受理/等待/恢复与图片节点共用同一代码路径，已回归覆盖）。
- WebM 上传探测（无时长/画幅解析）；视频转存受控流式落盘（本批仍为受控内存预算）。

## 真实供应商验证（2026-11-01，用户授权计费）

| 步骤 | 结果 |
| --- | --- |
| 协议探测（免费 400） | multipart 被拒（上游 Seedance 要求 JSON）；`sd_2.5_special_1080p` 上游 invalid_model（渠道问题）；`sd-mini_special_720p` 无渠道 |
| 直连受理 | `sd_2.0_special_720p` + JSON 提交 → 200 task_id，状态 queued→in_progress→completed（约 5 分钟），响应带 `video_url`（OSS 直链），/content 下载 3.4MB mp4 ✓ |
| 适配器修正 | JSON 提交、completed/in_progress 状态、video_url 解析、网关/上游双错误结构（单测同步更新 14/14 绿） |
| **后端全链路** | 创作项目发 text_to_video → QUEUED→RUNNING（上游 task_id）→ 约 4.5 分钟 → **SUCCEEDED**，产物 2.9MB 入素材库（origin=GENERATED, mediaType=VIDEO） |
| 元数据修复 | 发现 bug：`CreativeAssetRepository.insert` 缺 width/height/duration_ms 列（见 `docs/bugs/bug-2026-11-01-creative-asset-insert-drops-dimensions.txt`），修复后入库 1280×720 / 5062ms ✓ |
| UI 播放 | 预览弹窗实际播放 AI 生成视频（currentTime 至 2.94s/5.06s，1280×720，截图确认），元数据「2.9 MB · 1280×720 · 5秒」显示正确 |
| 绑定调整 | video_generation 绑定切换为 `sd_2.0_special_720p`（sd_2.5_special_1080p 渠道故障保留启用） |
| 计费记录 | 成功出片 2 次（探测 1 + 全链路 1，各 5 秒 720p）；失败 400 若干次未计费 |

## 测试资产

- 样片：`/tmp/aster-video-sample/flower.mp4`（MDN CC0 示例视频，1.1MB，960×540 / 5.06s）。
- 验收项目：「视频生成验收」（VIDEO），素材 flower.mp4（asset aae129d4…）。
- 桌面端验证记录、提交号在补齐提交后更新本文件。
