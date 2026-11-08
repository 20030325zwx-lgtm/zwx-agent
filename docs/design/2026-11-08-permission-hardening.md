# 权限硬化：动态风险分级 + L4 默认拒绝档 + 审批超时（2026-11-08）

## 背景

对照通用方案（RBAC/ABAC/风险分级/人工审批五层）做差距分析，当前实现主干已同构
（两维判定 + fail-closed + 审批闭环 + 幂等账本），补三个真实暴露面：

1. **风险分级静态化**——只看工具名不看参数：GUARDED 下终端 `ls` 与边缘命令一样逐次弹确认。
2. **缺 L4 默认拒绝档**——DESTRUCTIVE 已是最高档，YOLO 评测模式下 `queryExternalDatabase`
   （外部生产库）无遮拦。
3. **审批无超时**——用户不响应时 run 无限期悬挂。

明确不做（理由见文末）：RBAC 角色层、ABAC 资源级规则、Casbin/OPA、writeFile 覆盖确认。

## 改动

### 1. 参数感知动态风险分级（capability/tools）

- `ToolRiskCatalog.dynamicRisk(toolName, argsJson)`：纯函数参数规则，命中返回 `Optional<ToolRisk>`，
  未命中回落静态登记。当前唯一规则：`executeTerminalCommand` 首词 ∈ {ls, pwd, date, echo}
  且不含 shell 元字符（与 ToolGuardPolicy 拦截面一致）→ **READ_ONLY**。
  刻意排除 cat/grep/head/tail/find/python3——能读工作区外宿主文件，维持 DESTRUCTIVE 走确认。
- `ToolRiskCatalog.riskOf(toolName, argsJson)` = dynamic → orElse(静态)。
- `PermissionService.riskOf(toolName, argsJson)`：动态命中时**优先于 required-tools 抬升**
  （抬升语义是"保持既有拦截面"，动态规则只在参数层复核过无副作用时给更低等级）；
  未命中回落静态 + 抬升。
- `PermissionService.decide(runId, toolName, argsJson)` / 纯判定四参重载；
  `ToolGuardHook.confirmationGate` 传入 `call.arguments()`。

效果：EDIT/GUARDED 下终端 ls/pwd/date/echo 免确认（此前逐次弹确认），其余终端命令不变。

### 2. L4 FORBIDDEN 极高风险档

- `ToolRisk` 增加 `FORBIDDEN`（L1 READ_ONLY / L2 WRITE / L3 EXECUTE / L3+ DESTRUCTIVE / L4 FORBIDDEN）。
- `queryExternalDatabase` DESTRUCTIVE → **FORBIDDEN**（接触外部生产库，读也是数据外泄面）。
- 判定顺序：用户 DENY 规则 > 用户 ALLOW 规则 > **FORBIDDEN 一律 DENY（含 YOLO）** > 模式矩阵。
  唯一解锁途径 = 权限设置里显式添加 ALLOW 规则（管理员解锁语义）；DENY 规则仍优先。
- 未登记工具仍默认 EXECUTE（新工具/插件不因 L4 被误杀）。
- ToolGuardHook DENY 文案更新：提示"极高风险工具任何模式默认拒绝，需 ALLOW 规则解锁"。
- 前端：PermissionSettings / SuperAgent 模式提示同步；规则区加解锁说明。

### 3. 审批超时自动拒绝 + 自动恢复

- `ToolConfirmationService`：`app.tools.confirmation.pending-timeout-minutes`（默认 60，0=关闭）；
  `expireStalePending()` 用 `UPDATE ... WHERE created_at < NOW() - ?::int * INTERVAL '1' minute
  RETURNING run_id, tool_name` 落库 DENIED 并同步内存 scope（A0 闭锁同语义：先落库才生效，
  落库失败整体放弃）；`hasPending(runId)` 以库为准、失败按"仍有 pending"处理（宁可不恢复）。
- `PendingConfirmationSweeper`（agent/execution，@Scheduled 60s）：超时拒绝后逐 run 判断——
  仍 pending → 等；`agent_run.phase=STOPPED && status=INTERRUPTED` → 调 `AgentRunStateService.resume`
  走与用户手动决定完全相同的恢复链路（checkpoint + 幂等账本回放 + 确认门复检）。
- 拒绝是保守方向：恢复后模型收到「用户已拒绝」反馈，改用只读方式或说明限制收尾；
  不会无限循环（每轮拒绝都落库，同一工具不会再弹）。
- 竞态安全：用户手点与 sweeper 并发时，两边都是 `WHERE decision='PENDING'` 的 CAS，
  后到者落空；A0 闭锁保证不会凭内存状态放行。

## 测试

- `ToolRiskCatalogTest`（新，5）：纯环境命令降级 / 文件读取命令不降 / 元字符不命中 /
  参数缺失回落静态 / 静态档位与 FORBIDDEN。
- `PermissionServiceTest`（15）：FORBIDDEN 全模式 DENY + ALLOW 规则唯一解锁 + DENY 优先；
  动态降级在 EDIT/GUARDED 生效、cat/grep/元字符维持确认；riskOf(name,args) 优先级。
- `ToolConfirmationServiceTest`（6）：超时拒绝落库+内存同步 / 关闭与落库失败放弃 / hasPending 失败安全。
- `PendingConfirmationSweeperTest`（新，4）：挂起 run 自动恢复 / 仍有 pending 不恢复 /
  终态与 WORKING 不碰 / 单 run 恢复异常不串扰。
- `ToolGuardHookTest`（16）：确认挂起用例改 `cat`（ls 已被动态规则放行）；
  修复既有失败 approvedToolPassesAndDeniedToolAborts（见 bugs/bug-2026-11-08-confirmation-a0-test-stale.txt）。

## 明确不做

- **RBAC 角色层**：当前个人/小团队场景，per-user 规则够用，避免过早多租户化。
- **ABAC 资源级规则**：资源约束已由路径限制/表白名单在工具层兜底，规则引擎级 ABAC 等多用户产品化再议。
- **Casbin/OPA**：规则量级小，两张表 + 纯函数矩阵更可测；等策略热更新/多来源合并需求出现再上。
- **writeFile 覆盖转 EXECUTE**：agent 迭代交付物时高频重写同文件是正常工作流，
  逐次确认会严重劣化体验；且写面限定在会话工作区内，爆炸半径小。
- **容器沙箱**：既有 known-limitation（python3 可读宿主文件），独立基建项另行规划。
