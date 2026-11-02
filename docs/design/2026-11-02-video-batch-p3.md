# 批次生产（P3）实施记录

日期：2026-11-02。基线：P0+P1+P2 之后（`65c2625`）。承接调研方案 §6/§10 P3。**P4 成片未做**。

## 数据库（V18__video_batch.sql）

- `video_production_batch`：批次业务聚合（RUNNING/STOPPING/STOPPED/COMPLETED），`concurrency` 创建时冻结（默认 `app.story.batch.concurrency=2`），`scope_label` 记录业务范围（如"第 1 集《雨夜车站》"）。
- `video_batch_item`：`UNIQUE(batch_id, shot_id)` 唯一意图；状态 `PENDING_SUBMIT/SUBMITTED/DONE/FAILED/SKIPPED`；`candidate_id`/`job_id` 关联候选与任务；`attempt` 记录提交尝试（上限 3）。

## 后端

- `BatchProductionService`：创建（去重、单批 ≤12 镜头、逐镜头校验可生成且有提示词）；停止（`RUNNING→STOPPING`，未提交项→`SKIPPED`，在途不中断，全部收敛后 `STOPPED/COMPLETED`）；重试失败项（`FAILED→PENDING_SUBMIT`，已停止/完成批次回到 `RUNNING`）；进度聚合 **纯函数** `aggregate()`（待提交/在途/成功/失败/已停止分计数，完成率分母排除 SKIPPED）。
- `BatchSubmitScheduler`（2s）：对 RUNNING 批次按 `concurrency - 在途` 逐个提交；复用 `ShotProductionService.submitGeneration()`（候选预建 + prompt 冻结 + MediaJob 幂等受理，幂等键 `shot-{shotId}-{candidateId}`）；提交失败：未达 3 次重试 → 回待提交，达上限如实 FAILED；镜头状态版本围栏占位、失败回退 READY。
- `ShotCandidateResumeScheduler` 联动：候选终态（CANDIDATE/FAILED）→ 子项 `DONE/FAILED` → 批次结算（无待提交/在途即 COMPLETED/STOPPED）。
- `StoryController`：`POST/GET /story/batches`、`GET /batches/{id}`（批次+子项+进度）、`POST /batches/{id}/stop`、`POST /batches/{id}/retry-failed`。

## 前端

- `StoryWorkspace.vue`：分镜表多选（仅 DRAFT/READY 且有提示词可勾选）+「批量生成（n）」；批次面板显示状态、六项计数与完成率、子项标签（镜头 03·待提交）、停止/重试按钮；RUNNING 批次 3s 轮询。

## 语义与取舍（照调研 §6.2）

- 停止 = **停止未提交子项**；在途任务不自动取消（上游取消能力未验证，错误声称"已停止计费"是不诚实的）。
- 只重试失败项；已提交未知/成功项不重提。
- 批次不复制调度器状态：任务生命周期仍是 media_job 的唯一事实来源。
- 并发上限由批次冻结值 + 既有 GLOBAL/PROVIDER/USER 在途配额共同约束。
- 轮询不占提交配额（查询与提交分离在既有调度器设计中）。

## 验证

见 [验收证据](../test/results/2026-11-02-video-batch-p3.md)。本轮**无计费调用**（用户测试授权已用完）：提交内核复用已真实验证的 ShotProductionService；批次层面以单测 + 停止/重试/聚合 API E2E 验证。
