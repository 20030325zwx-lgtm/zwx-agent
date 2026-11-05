# 视频创作整改 P1/P2 实施记录（2026-11-06 夜间批次）

依据 `2026-11-05-video-creation-remediation-requirements.md` 依次实施遗留清单。
**约束**：全程未调用任何视频生成模型（未改视频绑定、未触发生成/批量接口、零计费调用）；
ffmpeg 相关测试全部使用本地合成片段（testsrc/sine），不访问外部服务。

## P0 收尾（ef1e118）

- 分镜页新增**表格视图**（列宽按需求 §7.5：序号/预览144/时长72/动作≥220/台词160/素材144/状态104），
  卡片/表格切换记忆在 localStorage；表格行内预览 muted 不自动播放。
- **检查器对齐需求 §5.4**：候选 → 提示词 → 首帧参考 → 模型参数 → 画风 → 高级剧情字段（默认折叠）；
  头部常驻保存状态（已保存/保存中/保存失败）。
- 候选元信息补**来源（生成/上传）· 创建时间 · 「较早设定」标记**（候选提示词与当前镜头不同即标记，
  不阻止采用，需求 §5.6）。
- **项目画风收敛到 Shell**：`projectStyle/projectTargetSeconds` 由 Shell 加载并提供，
  制作视图保存后路由切换即全局生效，画布节点/镜头/成片共用同一事实来源。

## P1（fc771ac / V45）

- **V45**：`video_shot` 增加 `style_code`（空=继承项目）与 `reference_asset_ids`（首帧，最多 1 张）。
- **生成预检**（`POST /story/generate-preflight`，纯校验不创建任务、不调用模型）：
  逐镜给出可提交/阻塞原因（状态/缺提示词/素材校验/模型能力）、实际 operation、秒数与任务数；
  **费用如实「费用未知」**。模型能力来自 `MediaModelResolver`（gk-video-3.5 的
  text_to_video=false 这类限制会在预检明确报出，A07）。
- **批量生成接预检**：确认面板列出范围/模型/任务数/费用与逐镜阻塞项，只提交就绪镜头（§5.5）；
  单镜生成同样先预检，阻塞给原因。
- **镜头级画风覆盖**：检查器可单独设置（镜头 > 项目默认），服务端校验编码、拒绝「跟随原片」。
- **首帧地基**：检查器上传/选择首帧图片；`submitGeneration` 按首帧自动选
  text_to_video / image_to_video 并传递参考素材（候选冻结带注记后的提示词）。

## P2 最小成片闭环（8da5b26 / V46）

- **V46** `video_final_cut`：DRAFT 草稿与导出运行快照同表不同行；`idempotency_key` 唯一防重复提交；
  状态机 `DRAFT → PREPARING → PROCESSING → UPLOADING → COMPLETED / FAILED / CANCELLED`。
- **`FinalCutService`**：按镜头顺序取每镜「已采用」候选重建草稿，缺片镜头如实返回
  （不阻止草稿，导出即「仅导出已选片段」，确认面板说明被排除镜头）；片段编辑乐观锁；
  导出校验素材 READY；取消/重试条件转移。
- **`FinalCutExportRunner`**（单并发调度，`app.final-cut.*` 可配）：
  下载受权素材 → ffprobe 时长 → 逐段规范化（**保比例补边、禁止拉伸**、统一 30fps/yuv420p、
  无音轨补静音、AAC 44.1k 立体声）→ concat 流复制 → 上传对象存储 → 登记 **DERIVED** 素材；
  整体超时/进程取消/临时目录隔离清理；状态只到阶段级（本地转码无百分比）。
- **产物链路**：播放/下载复用统一素材签名 URL（链接过期只重新签名）。
- **成片阶段 UI**：片段条（预览/顺序/移除）+「从镜头重新同步」+ 导出确认面板
  （顺序、输出画幅、费用说明「不调用生成模型」）+ 任务与历史（播放/下载/取消/重试）。
- **部署面**：`deploy/Dockerfile.backend` 内置 ffmpeg/ffprobe。

## 测试

- `FfmpegMediaIntegrationTest`：本地合成两段（一段无音轨）→ 规范化 → concat →
  探测总时长≈2s，验证补边/补静音/拼接在真实 ffmpeg 下可用（无网络、无生成模型）。
- `ShotProductionPreflightTest`：模型能力阻塞原因、就绪计数、operation 回显、未知镜头如实报错。
- 既有回归：StoryboardDraftServiceTest / CreativeCanvasServiceTest / PromptOptimizationModesTest 等通过。

## 边界（未做/待验收）

- 成片导出**真实多片段端到端验收**需要已选片的项目（涉及真实视频素材），留待用户线上验收；
  未做真实生成（按用户约束不调用生成模型）。
- 转场/裁切/字幕/配音未做（需求明确不列入首版）；音画规格为 30fps H.264/AAC 44.1k。
- P3 画布联动未动：镜头映射、候选回写、业务节点。
- `app.final-cut.enabled`（默认 true）可在服务器 .env 关闭整个导出域。
