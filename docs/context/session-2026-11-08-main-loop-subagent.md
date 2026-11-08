# 会话摘要：主循环 + spawnSubagent 终态架构（2026-11-08 下午）

## 决策

用户确认「子代理 = 工具」是最合理形态并授权推翻重来：主循环（唯一执行形态）+ spawnSubagent
工具（模型按需派生子代理），编排层（planner/workers/verifier/aggregator/路由分档）全部退役。
设计详见 `docs/design/2026-11-08-main-loop-subagent.md`。这是对 2026-11-07/08 两轮编排
改造（三档路由 → DirectNode）的进一步收敛——演进链条：固定图 → 三档 → 单循环默认 → 子代理即工具。

## 新组件

- `ParallelizingToolCallingManager`（capability/tools）：同 step 多工具并行执行（按调用粒度
  拆分走完整 delegate 路径、聚合按原顺序、失败包装不中断批次）；幂等账本跨线程
  （`ToolCallWorkerContext.capture/restore`，主线程预分配 invocation 序号保重放幂等）；
  专用 cached daemon 池（独立于 workerExecutor——spawn 子代理同步跑在并行池线程上再提交
  同池会死锁）。
- `SpawnSubagentTool`（agent/graph）：子代理即工具。深度=1（工具集去 spawn）、单 run 总数
  上限 12、结论提示词纪律（自包含/≤2000 字）+ 硬截断 4000、确认经共享 runId 冒泡、
  注册 `Workers` 进程级注册表（原 WorkersNode 实例字段提升）。
- `ToolCallAgent`：toolCallingManager 可注入；`DirectNode` 升级主循环（14 步、系统提示词
  加 spawn 使用纪律、HISTORY 会话上下文接入、并行管理器注入）。

## 退役删除（约 2200 行）

PlannerNode / VerifierNode / AggregatorNode / WorkersNode / WorkerRole / PlanJsonParser /
PlanGate 与 plan-proposed / revise 回路 / OrchestrationRouting / ManusRunRequest.orchestration /
GraphState 编排键（PLAN/TASK_RESULTS/VERIFICATION_*/REVISE_STEPS/ORCHESTRATION）/
对应测试 9 个文件。保留兼容：GraphCheckpoint 编排字段（老快照 JSON 可读，新写恒空）、
PlanStep（role 降级 String）、resumeNode 恒 direct（老节点名归 default）、
IntentRouter/Jev 槽位（评测平台用）。

## 过程中的问题（已修）

1. **ToolGuardHook 拦截 spawnSubagent（工具未登记）**——模型第一轮就正确发出了 3 个并行
   spawn，被守卫默认拒绝。修复：violationOf 加 case（task 非空校验）+ isKnownTool 登记。
2. **权限系统把 spawn 判 ASK 挂起**——GUARDED 模式下 spawn 被当副作用操作要求确认。
   修复：ToolRiskCatalog 登记 spawnSubagent = READ_ONLY（派生本身无副作用，子代理内部
   工具各自走评级与确认）。**教训：新增工具必须同步登记 ToolGuardHook + ToolRiskCatalog。**

## 验证

- 新测试 15/15（并行管理器 3：含 900ms→并行加速断言；spawn 4；DirectNode 5；编解码 3）；
  全量回归 147 类，失败 3 个均为既有（2 个 DashScope 欠费基线 + MediaJobServiceTest FK 清理）。
- 真实并行验证（本地）：主循环一次 turn 发 3 个 spawnSubagent → 三个子代理在 tool-parallel
  线程并行执行 → 结论回传 → 主循环汇总：COMPLETED / llm_calls=5（主 2 + 子 3）/ 21 秒。
  对照：旧图编排档同任务 ≈6 次调用 40-60 秒。
- 单循环回归：闲聊/线性任务仍走主循环（模型自决不派子代理）。

## 遗留

- spawnSubagent 恢复为子代理粒度重跑（内部调用由账本回放）——dsh 级子代理续跑需 P3 消息投影。
- 单步并行度目前由模型自决 + 池有界（cached 无上限）；观察到失控再加单 step 并行上限。
- 子代理 activity 已带 workerKey（subagent-N），前端时间线原生支持；无需前端改动。

## 注意

- 本轮含 push + 线上部署；上一批（意图路由 v2）为 0.7.29。
- 排查：日志线程名 `tool-parallel` = 并行工具执行；`subagent-N` = 子代理；
  `agent_run.last_node='direct'` 恒为主循环。
