# 创作画布 P1：执行到此节点/继续执行 + 拖线双连（2026-11-05）

## 改动内容

用户提议三项画布优化，本轮为第一期（up_to 运行范围 + 生成节点间一次拖线双连），P2 节点结果覆盖编辑、P3 完整结果边未做。

- 后端 `CreativeCanvasService` 新增 `up_to` 运行范围：included = 选中节点 + 祖先闭包 ∩ 生成节点，其中最新持久化状态为 SUCCEEDED 的祖先跳过（排除出 included，复用既有 `withVerifiedParents` 机制回填其 `output.assetId` 供下游字面量引用），FAILED/CANCELLED/SKIPPED/未运行的重跑，RUNNING 阻断并带落库 step message 提示（选片等待落库为 RUNNING）。选中节点本身始终重跑（即使已成功，等价重新生成）。
- 实现方式：`includedNodes`/`requiredExternalParents`/`prepareGraph` 增加 skipIds 参数（旧三参重载保持兼容）；状态查询抽成共用 `latestExecution`（SELECT 增加 message 列）；`resolveUpToSkips` 在 `startRun`/`validateRun` 中先于 withVerifiedParents 执行。WorkflowService 零改动，快照/调度/选片/取消全部复用。
- `normalizeScope` 白名单与错误消息加入 `up_to`。
- 前端 `canvasGraph.js`：`dependencyIssue` 增加 up_to 分支（祖先闭包纳入、已成功祖先剔除、RUNNING/QUEUED/SUBMITTING/WAITING_EXTERNAL 阻断、WAITING_SELECTION 提示先选片）；新增 `siblingConnection`（生成节点间拖一条内容边时计算兄弟类型边，重复/循环/视频源等非法时返回 null）。
- 前端 `ProjectCanvasTab.vue`：工具栏运行范围加「执行到此节点」；生成节点检查器新增「执行到此节点/继续执行到此节点」按钮（存在已结束运行且选中节点未完成时显示后者），直接调 `runCanvas('up_to')`；`connectNodes` 自动补连兄弟边——目标是图片节点静默补连并 toast「已同时连接提示词与图片素材」，目标是视频节点先 `window.confirm`（首帧是强语义承诺），拒绝则只连拖的那条；新增中性色 `.canvas-notice.info` 提示条。
- 冒烟测试同步调整：生成节点间拖线边数断言 +2（自动双连）；新增 up_to 提交断言与视频确认分支（拒绝/接受两条路径）。
- 用户反馈追加：连线要支持删除。原仅有双击断开（无可见入口、从未测过）与检查器断开按钮；本轮新增键盘删除——选中连线或非开始节点后按 Delete/Backspace 即删除（`onCanvasKeydown`，输入框聚焦时忽略，开始节点受保护），底部引导文案追加「双击或选中按 Delete 删除连线」。冒烟新增四项断言：双击删线、选中线 Backspace、选中节点 Backspace + 撤销、开始节点 Backspace 无效。注意：确认分支重建的兄弟边使用 crypto.randomUUID() id，测试选择器须用 ensureStart 的确定性 id（canvas-start-*）。

## 验证结果

- 后端定向测试：CreativeCanvasServiceTest 15（新增 4 项：up_to 闭包/旁支排除、跳过已成功并复用产物、RUNNING 阻断、选片等待阻断）+ WorkflowCanvasDependencyTest 1 + WorkflowRerunTest 4 + WorkflowReferenceResolutionTest 2 + WorkflowGraphValidationTest 5 + WorkflowInputsValidationTest 2，共 29 项通过，0 失败。
- `node --test frontend/tests/canvasGraph.test.mjs`：8/8 通过（新增 up_to 状态矩阵与 siblingConnection 用例）。
- `npm run build` 通过（2m56s，仅既有大 chunk 警告）。
- 浏览器冒烟 `PLAYWRIGHT_CHANNEL=chrome NODE_PATH=services/browser-mcp/node_modules node frontend/tests/canvas-browser-smoke.cjs`：两次运行均通过（passed: true），覆盖双连静默路径、重复兄弟边拒绝、up_to 提交 scope/nodeId 断言、视频首帧确认拒绝/接受、既有回归项。截图 /tmp/aster-canvas-verification/。
- `mvn -DskipTests package` 通过，JAR 已更新；launchctl 前后端已重启，`/api/health` 返回 ok，前端 HTTP 200，启动日志无 ERROR；`git diff --check` 通过。
- 未调用真实付费模型 API；未读取、修改或重建 application-local.yml。

## 遗留事项

- 冒烟测试中「添加视频生成节点」后新节点手柄可能落在视口外（addNode 的 fitView 未见效，实测 handle x=1457 > 1440），已改为在既有节点对上测确认分支绕开；addNode 的 fitView 定位问题本身待单独排查（疑似与 Vue Flow 尺寸测量时序有关），与本次功能无关。
- up_to 的"已成功祖先跳过"读取的是最近一次含该节点运行的结果；若用户改过上游提示词后直接 up_to，下游会引用旧图（与 from_here 现状一致），陈旧标记属 P2 范围。
- 服务端仅阻断持久化状态为 RUNNING 的祖先；PENDING/QUEUED 步骤（活跃运行中）会被纳入重跑，与现有无跨运行并发防护的姿态一致，未新增守卫。
- 真实供应商整链未做付费验收；交互需用户登录后人工体验确认。
- 上一会话遗留：`e1426b9` 未推送、定时任务规划文档未提交，本轮未处理、未提交、未推送任何 Git 变更。

## 环境注意事项

- 前端 http://127.0.0.1:3000/；后端 http://127.0.0.1:8123/api；launchctl：com.zwx.yu-ai-agent.frontend / backend，后端改动必须先 package 再 kickstart。
- 冒烟测试 NODE_PATH 用仓库内 services/browser-mcp/node_modules（含完整 playwright），PLAYWRIGHT_CHANNEL=chrome。
- 后端定向测试用 `-Dtest=...` 指定类，勿跑全量（SpringBootTest 本机挂起，既有约定）。
