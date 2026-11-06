# 会话摘要：视频创作整改 P1/P2 夜间批次（2026-11-06）

## 背景

用户要求按遗留清单依次整改测试，**硬约束：不调用任何视频生成模型**（不绑视频模型、不触发生成、零计费），
无需确认，早上看成果。全程遵守：视频绑定未动（仍 gk-video-3.5），未触发生成接口，ffmpeg 测试用本地合成片段。

## 交付（三个提交）

1. **ef1e118 P0 收尾**：分镜表格视图（列宽规格/切换记忆）、检查器字段顺序对齐
   （候选→提示词→首帧参考→模型参数→画风→高级折叠）+ 头部保存状态、候选元信息（来源/时间/较早设定）、
   项目画风收敛到 Shell 全局生效。
2. **fc771ac P1 + V45**：生成预检 API+批量确认对话框（纯校验、费用未知、逐镜阻塞原因——
   gk-video-3.5 不支持文生视频这类限制现在直接可见）；镜头级画风覆盖（V45 style_code）；
   首帧参考地基（V45 reference_asset_ids，检查器上传/选择，生成按首帧自动选 operation）。
3. **8da5b26 P2 + V46**：成片闭环——草稿（按镜头顺序取已采用候选、缺片如实返回）、
   导出（幂等键、快照冻结）、单并发 ffmpeg 执行器（保比例补边/补静音/concat/超时/取消/临时目录清理）、
   DERIVED 产物入库走统一素材链路、成片阶段 UI（片段条/确认面板/历史/播放下载）、
   后端镜像内置 ffmpeg。

## 验证

- `FfmpegMediaIntegrationTest`（本地合成片段真实 ffmpeg，验证补边/补静音/拼接/探测）通过；
- `ShotProductionPreflightTest` 等新增单测通过；creative 模块回归通过（两个既有失败与本轮无关）；
- V45/V46 已随本地重启应用（0.09s/0.085s）；`npm run build` 通过；本地 health ok。

## 遗留 / 明早验收清单

- 线上走通：制作视图 → 表格视图 → 批量生成预检（会看到 gk-video-3.5 文生视频阻塞原因）→
  检查器给镜头设置首帧图 → 单镜生成（图生视频，**真实计费**，由用户决定）→ 选片 →
  成片阶段同步草稿 → 导出 MP4 → 播放/下载。
- 视频模型两条路仍未拍板（绑 sd 试文生视频 / 用首帧走 gk-video-3.5）。
- P3 画布联动未动；转场/裁切/字幕未做（需求明确不列入首版）。

## 环境注意

- 服务器部署需重建 backend 镜像（新增 apt ffmpeg 层，构建会变慢几分钟）。
- `app.final-cut.*` 可配：ffmpeg/ffprobe 路径、轮询间隔、超时分钟数、enabled 开关。
- 视频约束期间生成的测试全部是 mock/本地合成，无任何计费。

## 部署验收（0.7.16，2026-11-06 03:0x）

- 提交 ef1e118 / fc771ac / 8da5b26 / a29aaf8 / 4de1521 已推送并部署 0.7.16：
  backend healthy、公网 health OK、前端资产哈希一致（index-aImWQSKr.js）。
- 迁移：V44/V45/V46 全部 success；DB 备份 backups/backup-2026-11-06-0259.sql。
- 服务器 backend 容器内实测 ffmpeg 8.0.1 + /usr/bin/ffprobe 可用。
- 全量回归仅剩 3 个与本次无关的失败：ToolGuardHookTest、MediaJobSchedulerTest（既有）、
  PgVectorVectorStoreConfigTest（DashScope 欠费，用户明确暂不处理知识库 embedding）。
- 新增修复：MediaJobServiceTest 清理兼容同 JVM 调度线程干扰（重试删除）；
  VideoFinalCutRecord 的 running/draft 辅助方法改为 isXxx 才能被 Jackson 序列化（取消按钮/轮询依赖它）。

## 视频模型切换（2026-11-06 白天，用户指示）

- `video_generation` 用户绑定从 `gk-video-3.5`（街影，仅图生视频）切到
  **`sd_2.5_special_1080p`（KKAI 视频中转站，Seedance 2.5，openai-video 适配器，t2v+i2v 均 true）**。
- 未做真实生成验证（按用户之前约束不擅自调用计费接口）；首次生成由用户验收。
- 备选：`sd_2.0_special_720p`（row 34）已启用，可随时改绑；`sd_*_with_video_ref` 系列未启用。
- 待观察：Seedance 时长档位（常见 5/10 秒）若与镜头 1–60 自由秒数冲突，
  首条失败后按模型能力收敛镜头时长选项。
