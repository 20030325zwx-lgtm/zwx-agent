# 视频批次生产（P3）会话（2026-11-02）

## 改动内容

实施记录：`docs/design/2026-11-02-video-batch-p3.md`。承接 P0+P1+P2（`65c2625`），落地调研方案 P3：

- **V18 迁移**：`video_production_batch`（RUNNING/STOPPING/STOPPED/COMPLETED，创建时冻结 concurrency）+ `video_batch_item`（UNIQUE(batch_id, shot_id)，PENDING_SUBMIT/SUBMITTED/DONE/FAILED/SKIPPED，candidate/job 关联，attempt 上限 3）。
- **后端**：`BatchProductionService`（创建校验/停止=停止未提交项/只重试失败项/进度纯函数聚合）；`BatchSubmitScheduler`（2s，按并发逐个提交，复用 ShotProductionService.submitGeneration 的候选预建+幂等受理，失败重试 3 次后如实 FAILED）；`ShotCandidateResumeScheduler` 联动子项终态与批次结算；StoryController 批次五端点。
- **前端**：分镜表多选 + 批量生成按钮 + 批次进度面板（六项计数/完成率/子项标签/停止/重试，RUNNING 时 3s 轮询）。

## 验证结果

- BatchProgressAggregateTest 4/4；P12 单测 6/6。
- 定向回归 24 类：**134/135**；唯一失败为既有环境敏感类 `MediaJobSchedulerTest`（云库延迟下失败项轮换，单测可过，本地 Docker 库曾 12/12）。
- 修复 3 处测试清理外键顺序（否则真实调度数据导致清理失败）。
- E2E 无计费：批次停止（PENDING→SKIPPED、批次 STOPPED、完成率 0%）、重试失败项（FAILED→PENDING、批次 RUNNING）、前端面板渲染全部通过。
- 真实批次生成未执行（计费授权已用完，零计费）；提交内核复用已真实验证路径。
- 缓存清理：`mvn clean` + 前端 `node_modules/.vite`/`dist` 清理后重新构建打包；桌面端重打包。

## 遗留事项

- P4 成片（时间线/字幕/MP4 导出、ffmpeg 受控运行时）未做。
- 真实批次提交与上游取消能力待授权后验证。
- **环境**：数据库已换云服务器库（本地容器停止）；集成测试前必须 `launchctl remove` backend（否则自动拉起与测试抢任务）并清理测试用户残留；跑完 `launchctl submit` 恢复。
- 改动已提交（见 git log）。

## 环境注意事项

- application-local.yml 未读取改写（仅清理脚本按需读取连接串，未输出密钥）。
- 桌面端 release/ 已更新。
