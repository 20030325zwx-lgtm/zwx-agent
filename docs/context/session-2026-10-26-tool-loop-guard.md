# 会话交接：工具循环防护补全（2026-10-26）

> **已被 `session-2026-10-27-p0-p2-hardening.md` 取代**；本份改动已提交（3ba5044）。
> 本份作为「最新一份摘要」；上一份 `session-2026-10-26-token-usage-accounting.md` 的改动与遗留事项仍然有效（其第 3 节遗留项未动，除下文第 2 节新增项外）。

## 1. 改了什么

**目标**：接续上一份摘要的「死循环如何合理解决」讨论，补全指纹校验三处缺口。bug 记录见 `docs/bugs/bug-2026-10-26-tool-loop-guard-never-fires.txt`。

- 新增 `agent/core/ToolLoopGuard`（package-private，纯确定性逻辑，不经过模型、不经过 hook 管线）：
  - 参数指纹（工具名+参数）：同参数非生产性调用累计 `MAX_SAME_ARGS_CALLS=3` → 停机；
  - 结果指纹（工具名+归一化结果）：连续 `MAX_NO_PROGRESS_ROUNDS=5` 轮无新有效结果 → 停机；
  - **有新有效结果的轮次豁免参数循环判定并清零无进展计数**（保护同参数重试成功/翻页场景）；
  - 空执行列表（本步未调工具）不计数。
- `BaseAgent`：删除死阈值 `MAX_SAME_TOOL_RESULT_CALLS=53` 与内联指纹块；`runStream()` 改用 ToolLoopGuard（命中走既有 requestStop → 中断摘要）；**同步 `run()`（图 worker 路径）首次接入防护**，命中时 `fireOnInterrupted` + 追加「任务已按策略终止：<原因>」并 FINISHED。
- 测试：重写 `BaseAgentTerminationTest`（4 旧例改语义 + 4 新例），新增 `ToolLoopGuardTest`（4 例）。

## 2. 验证结果

- `mvn clean test`：**262 项通过，0 失败 1 跳过**（跳过项为走真实 DashScope API 的 ZwxManusTest）。
- `mvn clean package -DskipTests` 出包 23:31；`launchctl kickstart -k` 重启后 `/api/health`=ok。
- 语义要点（评审时注意）：一步内 3 个相同调用若结果有效且首次出现，会算「有进展」放行，同参数紧循环在**下一步**（结果不再新）才熔断——这是为保护正当重试的有意取舍，不是漏判。

## 3. 遗留事项

0. 上一份摘要第 3 节全部遗留项继续有效（A 档②'/②/③、压缩无损化+评测集、Jev 知识库误判、两仓库未 push 提交、测试 run 未清理、执行轨迹图路径未接线）。**本次改动未提交。**
1. 循环防护的「软干预」级未做：检测到重复时先往消息历史注入提醒让模型自纠，再犯才硬停（现为一发现即停）。
2. 跨 worker 的每 run 重复调用统计未做：可仿 TokenUsageAdvisor 挂账，进 RunInspector「用量」页。
3. `isMeaningful` 的「失败/无法」子串启发式未动（正文含这些词的有效结果不计为进展），已知取舍，见 bug 记录。

## 4. 环境注意事项

与上一份摘要第 4 节相同：改后端必须 `mvn -DskipTests package`（建议先 clean）再 `launchctl kickstart -k gui/$UID/com.zwx.yu-ai-agent.backend`；`application-local.yml` 不得动。
