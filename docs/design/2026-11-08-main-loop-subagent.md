# 主循环 + spawnSubagent：agent 执行形态终态（2026-11-08）

## 决策

用户确认「主循环 → 模型自派子代理 → 结论压缩回主循环」是最合理形态，授权推翻现有设计。
对标：Claude Code 的 Task 工具、deepseek-harness 的 subagent session、ZCode 的子代理。
这是编排演化的自然终点——**派不派子代理的判断跟着模型能力走，换更强的模型并行决策自动变好，
不用改一行路由代码**。

## 终态架构

```
图壳（保留空壳：START → direct → END，checkpoint/事件驱动逻辑挂在上面）
  └─ 主循环（DirectNode，一个 ReActAgent，14 步上限）
       ├─ 全工具 + 会话历史 + 知识注入 + hook 管线 + 并行工具执行
       └─ spawnSubagent 工具（模型按需调用）
            ├─ 子代理 = 独立上下文 ReAct 循环（10 步、全工具减 spawn、深度=1）
            ├─ 并行 = 主循环一个 turn 发 N 个 spawn 调用 → 并行工具执行器
            ├─ 结论压缩回传（提示词纪律 ≤2000 字 + 硬截断 4000 字）
            └─ 确认冒泡（共享 runId → 同一确认队列 → run 挂起 → 账本回放继续）
```

## 新组件

### ParallelizingToolCallingManager（capability/tools）
同 step 多工具调用并行执行——「子代理=工具」的执行前提（串行会把并行收益变成等待总和）。
- 实现按调用粒度拆分：每个单调用走完整 delegate 路径（保留框架异常包装/空结果语义），
  聚合 ToolResponseMessage 按原顺序拼回 conversationHistory；单调用直调 delegate 零开销
- 幂等账本跨线程：主线程按调用顺序预分配 invocation 序号（重放幂等的前提），
  子线程 `ToolCallWorkerContext.restore` 恢复 worker 身份
- 专用 cached+daemon 池（独立于 workerExecutor）：spawn 子代理同步跑在并行池线程上，
  其内部并行工具再向同池提交——共用固定池会互相饿死（死锁规避）
- 工具失败包装为错误结果返回模型（不中断同批其他调用）

### SpawnSubagentTool（agent/graph）
- 工具 schema：单参 task（自包含任务描述）；描述里写明使用纪律（何时派/何时不派）
- 子代理 = ToolCallAgent 实例（复用 hook 管线/确认/账本/事件日志），结论取最后一条
  assistant 消息；三道闸：深度=1（工具集去 spawn）、单 run 总数 12、单步并行池有界
- 注册 Workers 进程级注册表（原 WorkersNode 实例字段提升），停止请求覆盖子代理

## 退役删除（约 2200 行）

PlannerNode、VerifierNode、AggregatorNode、WorkersNode（编排调度）、WorkerRole（角色体系）、
PlanJsonParser、PlanGate/plan-proposed/revise 回路、OrchestrationRouting（路由分档）、
ManusRunRequest.orchestration、GraphState 编排键（PLAN/TASK_RESULTS/VERIFICATION_*/REVISE_STEPS/
ORCHESTRATION）、resume 计划确认门逻辑。
保留兼容：GraphCheckpoint 编排字段（老快照 JSON 可读，新写入恒空）、PlanStep（role 降级为
String）、resumeNode 恒 direct（老 checkpoint 的 planner/workers 等节点名归入 default）。
保留：IntentRouter/IntentDecision/JevIntentRouter 槽位（评测平台在用）、WorkspaceRequestDetector
（项目工作区服务解析）。

## 恢复语义

- 检查点归属恒为 direct 节点；恢复 = 重跑主循环
- 主循环已完成的普通工具调用由幂等账本回放；**spawnSubagent 记账本但子代理粒度重跑**
  （子代理内部已成功调用同样由账本回放，不重复副作用）——dsh 的子代理续跑依赖消息历史
  投影（P3），v1 接受子代理粒度重跑并如实标注

## 测试

- ParallelizingToolCallingManagerTest 3：多调用并行加速（900ms→<850ms 断言）+ 顺序聚合、
  单调用直调、失败包装不中断批次
- SpawnSubagentToolTest 4：结论回传、空 task 不触发模型、数量上限熔断、schema 纪律
- DirectNodeTest 5（改造）：流式回答/停止早退/提示词纪律/恢复恒 direct/事件常量
- 删除编排测试 9 个文件；全量回归见会话摘要
