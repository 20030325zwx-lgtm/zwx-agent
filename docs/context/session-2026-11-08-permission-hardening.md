# 会话摘要：权限硬化——动态风险分级 + L4 默认拒绝档 + 审批超时（2026-11-08 晚）

## 需求

用户对照通用权限方案做差距分析后要求「把该补的补上」。三件套：①参数感知动态风险分级；
②L4 FORBIDDEN 默认拒绝档（含 YOLO）；③审批超时自动拒绝。设计详见
`docs/design/2026-11-08-permission-hardening.md`（含明确不做项：RBAC/ABAC/Casbin/OPA/
writeFile 覆盖确认/沙箱）。

## 改动

- `ToolRisk` 加 FORBIDDEN；`queryExternalDatabase` DESTRUCTIVE→FORBIDDEN。
- `ToolRiskCatalog.dynamicRisk(toolName, args)`：终端首词 ∈ {ls,pwd,date,echo} 且无 shell
  元字符 → READ_ONLY（刻意排除 cat/grep/head/tail/find/python3——能读工作区外宿主文件）；
  `riskOf(toolName, args)` 动态命中优先于静态。
- `PermissionService`：判定顺序 DENY 规则 > ALLOW 规则 > FORBIDDEN 一律 DENY（含 YOLO）>
  模式矩阵；`riskOf(toolName, args)` 动态命中跳过 required-tools 抬升；decide/run 判定贯通 args。
- `ToolGuardHook`：confirmationGate 传 `call.arguments()`；DENY 文案说明 FORBIDDEN 解锁途径。
- `ToolConfirmationService`：`pending-timeout-minutes`（默认 60，0 关）；
  `expireStalePending()` UPDATE..RETURNING 落库 DENIED + 内存同步（A0 闭锁：先落库才生效）；
  `hasPending()` 以库为准、失败按「仍有 pending」。
- `PendingConfirmationSweeper`（新，60s）：超时拒绝后对 `phase=STOPPED && status=INTERRUPTED`
  且无剩余 pending 的 run 调 `AgentRunStateService.resume`，与用户手动决定同链路恢复。
- `application.yml`：confirmation 块补 `pending-timeout-minutes` 与抬升语义注释。
- 前端：PermissionSettings/SuperAgent 模式提示更新（外部库移出 GUARDED、YOLO 例外说明、
  规则区加 ALLOW 解锁说明）。

## 测试与既有失败修复

- 定向 52/52 绿：ToolRiskCatalogTest(5 新) / PermissionServiceTest(15) /
  ToolConfirmationServiceTest(6) / PendingConfirmationSweeperTest(4 新) / ToolGuardHookTest(16)。
- **全量回归 627 例中揪出 3 个 HEAD 上即失败的滞后测试并修复**（均在 HEAD worktree 实证）：
  ①ToolGuardHookTest A0 闭锁未 stub（bug-2026-11-08-confirmation-a0-test-stale.txt）；
  ②ManusGraphOrchestratorTest 仍断言四阶段图、③AgentRunViewTest 仍断言中文角色标签
  （bug-2026-11-08-orchestration-retirement-stale-tests.txt）。
- 剩余失败全部为既有基线（创作模块本地库 FK 状态 11F + DashScope 欠费 PgVector 等 3E，
  HEAD 同样失败）。
- 教训（两次重复出现）：回归报告「既有失败」只记数量不核根因，会让滞后测试伪装成基线——
  已写入两个 bug 记录的后续注意事项。

## 遗留

- 动态风险规则目前仅终端一条；downloadResource 大小分级（需预检 Content-Length）、
  writeFile 覆盖确认（明确不做，理由见设计文档）可后续按需加。
- 审批超时默认 60 分钟；前端未加倒计时展示（超时拒绝在轨迹中以模型反馈体现）。
- 创作模块测试基线（FK 清理/欠费）依旧未修，属创作模块测试基建。

## 注意

- 部署版本随本批递增（deploy 脚本自动 +1）；`queryExternalDatabase` 上线后默认 DENY，
  如有用户/评测在 YOLO 下依赖外部库查询，需在权限设置加 ALLOW 规则解锁。
