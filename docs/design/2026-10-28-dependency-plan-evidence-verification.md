# 依赖计划与证据验收（2026-10-28）

> 状态：已实施（P0 三项中的依赖计划、步骤状态与证据验收；Goal 模式、任务中心等 P1 项未做）。
> 参考 EvovexAI/EvoFlow 的 Plan 模式设计思路（`depends_on` 波次执行、先对齐后执行）；仅借鉴思路，未使用其代码（该项目为 PolyForm Noncommercial 许可）。

## 1. 背景与目标

原编排为 `planner → workers（全部并行）→ verifier →（revise 全量重跑）→ aggregator`：计划只有标题/角色/描述，多步任务无法表达先后依赖（如"调研 → 分析 → 写报告"）；质检主要依赖模型读报告文本，"报告生成了"这类说法可能无实际文件支撑；返工时所有子任务重跑。

本次改进三个 P0：

1. **任务依赖的执行计划**：步骤带 id、`depends_on`、验收判据（acceptance），按依赖分波执行，上游结论注入下游任务提示。
2. **选择性返工**：质检点名步骤（`revise_steps`），未点名的步骤复用上一轮结论。
3. **基于证据的验收**：结构化证据门禁（失败/空结果、承诺产出文件却没有文件名）不通过直接返工；模型评价对照每步完成判据。

## 2. 方案

### 2.1 计划结构（PlanStep）

`PlanStep(id, title, role, detail, dependsOn, acceptance)`：

- `id`：`s1..sn`，由 `PlanJsonParser.normalize` 按位置统一重编（声明 id → 规范 id 建映射，容忍缺省/重复）。
- `dependsOn`：只保留可解析引用，丢弃未知引用与自引用；**断环**——反复求解可满足依赖的步骤，环内步骤清空依赖退化为并行，保证调度不死等。
- `acceptance`：一句可验证的完成判据，planner 提示词要求给出（可选），供质检门禁与模型评价使用。
- 保留三参旧构造，旧调用点与旧 checkpoint JSON（缺字段 → null）自动兼容。

### 2.2 波次调度（WorkersNode）

- `dependencyWaves(plan)`：按 Kahn 分层，第 n 波的依赖都在前 n-1 波；空 id/未知依赖视为无依赖。
- 执行循环：每波内并行提交（沿用现有 workerExecutor + 确认门禁 + 停止检查），波间串行。
- **上游结论注入**：执行某步前，把其依赖步骤的可复用结论（压缩至 600 字）以"上游任务结论（可直接引用，不得虚构）"拼进任务提示——下游拿的是上游真实产出而非计划描述。
- **失败传播**：某步失败/被跳过/未启动时记入 broken 集合，其下游步骤标记"上游任务失败或被跳过，本步骤未执行"并跳过，不波及无依赖的旁支。
- 结果按计划对齐：每轮返回 `plan.size()` 条（含复用/跳过占位），与 checkpoint 的追加语义配合"取最后一轮"视图。

### 2.3 选择性返工（REVISE_STEPS）

- `GraphState.REVISE_STEPS`（replace 策略）：verifier 输出点名序号，workers 读取。
- 返工轮：`TASK_RESULTS` 追加语义下取最后一轮；未点名的步骤且结论可复用（非失败/跳过/取消/未启动）直接复用并发出"复用上轮结果"事件，只重跑点名步骤。
- checkpoint 增加 `reviseSteps` 字段并在 `resumeInputs` 恢复；**未加数据库列**——旧 11 参构造默认空列表，进程重启后的返工恢复降级为全量重跑（与旧行为一致）。
- aggregator 同样取最后一轮对齐结果，避免上一轮旧结论混入最终答案。

### 2.4 证据验收（VerifierNode）

1. 挂起/达到返工上限的早退逻辑保持不变；
2. **结构化证据门禁**（不进模型，直接判 revise 并点名）：结果为空；失败/跳过/取消；步骤承诺产出文件（author 角色，或 detail/acceptance 匹配"生成|输出|导出|保存 + 文件|PDF|表格|文档"）但结果文本中没有任何文件名；
3. **模型评价**：核对文本逐条给出完成判据与结果摘要（600 字），verdict 可带 `steps`（0 起序号）点名返工，省略/为空表示全部重做；
4. 门禁通过但达到返工上限时仍标记 unverified（不二次模型验收，避免死循环）。

## 3. 前端

- RunInspector「计划」页签：展示依赖（`依赖 01、02`，按 id 反查序号）与验收判据。
- 语义事件（agent-trace 时间线）自动呈现波次执行、复用、跳过过程，无需改动。

## 4. 验证

- `mvn test`：327 通过、0 失败、1 跳过（新增 `PlanJsonParserTest` 依赖用例 4 个、`WorkersNodeDependencyTest` 6 个、`VerifierNodeEvidenceTest` 7 个）。
- `mvn -DskipTests package` 成功；后端经 launchctl 重启，`GET /api/health` 返回 ok；`npm run build` 通过。

## 5. 遗留与后续（P1）

- planner 尚无"计划草稿/修订/激活"交互（EvoFlow Plan 模式的先对齐后执行）；
- 步骤级 checkpoint 粒度仍是节点级（workers 节点整体落盘），单步中断恢复未做；
- 证据门禁的文件核对基于结果文本，未直接校验工作区文件系统；
- P1：跨会话任务中心、Goal 模式、可配置 Agent 能力组合、任务经验复用、运行中预算控制、端到端评测集。
