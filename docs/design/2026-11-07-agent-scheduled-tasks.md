# 定时任务：到点拉起智能体无人值守干活（2026-11-07）

对标 ZCode automations / Codex scheduled tasks：任务 = 自然语言指令 + 节奏 + 权限档 + 专属会话。
到点由调度器以该指令在智能体运行时里发起一次无人值守运行；产出、轨迹、确认、配额全部复用现成底座。

## 设计要点

- **零新建执行层**：`AgentRunService/AgentRunStateService`（运行）、`PermissionMode` 五档
  （GUARDED 默认：创作免确认，终端/Git 提交等高危挂起等人）、`RunQuotaService`（配额）、
  运行轨迹页（前端）全部现成。新增的只有"时间触发 + 任务定义 + 触发记录聚合"。
- **会话连续**：任务默认绑定专属会话（首次触发创建后回填 conversation_id），
  第二次运行能引用第一次的产出——ZCode automations 同款语义；可选「每次全新」。
- **无人值守指令包装**：指令末尾追加固定前言（无人在线、不要提问、结束给简短结论），
  降低 AskHuman 挂起概率；GUARDED 的高危挂起仍保留为安全阀。
- **多实例安全**：本地后端与服务器后端连同一个库、都跑 `@Scheduled`——认领用
  `FOR UPDATE SKIP LOCKED`（与媒体任务调度器同款），双实例并发不会双触发；
  顺延 next_run_at 与认领同事务。
- **补跑策略**：错过执行点 → 恢复后由下一个 tick 自然补跑一次（next_run_at 判定），
  不按错过次数连发。
- **失败隔离**：单次触发失败只落 fire.error，调度批次与任务照常；ONCE 失败同样顺延禁用
  （记录已存在，不静默吞）。

## 数据模型（V47 + V48）

`agent_scheduled_task`：instruction、conversation_id（回填）、session_continuity、
permission_mode（默认 GUARDED）、model_row_id（NULL=默认对话绑定）、
schedule_kind(DAILY/WEEKLY/ONCE) + run_time/weekly_dow/once_at、enabled、last_run_at/next_run_at。
`agent_scheduled_fire`：task_id、run_id、trigger_kind(SCHEDULE/MANUAL)、error、started/finished_at。
时间计算固定 Asia/Shanghai（`ScheduleTimes`，纯函数），与实例机器时区无关。

## 触发链（dispatch）

`runQuota.checkRunAllowed → ensureConversation → startUserTurn(clientRequestId=sched-<fireId> 幂等)
→ 历史拼装 → StartCommand(executionMode=SCHEDULED) → appendAssistantReply(IN_PROGRESS)`
——与 ManusAgentController 的 v2 发起完全同构，仅来源标记不同。

## 前端

「更多」分组「定时任务」（/schedules，CalendarClock）：任务列表（名称/权限徽标/节奏/下次运行/
最近结果）、等待确认置顶亮条（一键跳 `super-agent?conversationId=`）、立即运行、暂停/启用、
行内运行历史（phase 徽标 + 错误 + 查看运行深链接）、新建对话框。

## 验证（2026-11-07 实测）

- `ScheduleTimesTest` 5/5：每天顺延、每周跨周不重复、ONCE 过期拒绝、中文节奏描述。
- 本地端到端：建 DAILY 任务 → next_run_at=次日 09:30（北京）✓；「立即运行」→ 智能体真跑
  并回复 → fire phase=DONE ✓；建 ONCE(+90s) 任务 → 调度器 30s 内自动触发（trigger=SCHEDULE）
  → 跑完自动禁用 ✓；失败路径：中转站 429 时 fire.error 如实记录、调度器不受影响 ✓。
- 测试任务已删除，不留计费尾巴。

## 边界（v1 未做）

maxRuns 次数上限、cron 表达式、推送通知（站内「生成活动」已有）、定时跑画布流程
（选片等待与无人值守的交互待设计）、任务级并行上限。
