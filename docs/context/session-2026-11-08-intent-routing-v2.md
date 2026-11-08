# 会话摘要：意图路由 v2——删除前置 LLM 分类器，信号分层路由（2026-11-08 中午）

## 需求与设计

用户拍板「qwen 模型不再参与意图识别」并要求参考 deepseek-harness 与 ZCode 重新设计。
调研（dsh 本地源码）：**两个成熟 harness 都没有前置任务意图分类器**（dsh 源码里的 "intent"
是 UI surface 渲染意图；ZCode 靠用户显式模式 + skills description 匹配 + 主模型自路由）。
设计定稿：`docs/design/2026-11-08-intent-routing-v2.md`——信号从贵到便宜分层，
**默认单循环，硬规则+并行证据升级图编排**。

## 改动

- 新增 `OrchestrationRouting`（platform/intent）：decide(workspace, hasImages, message, enabled)
  + hasParallelEvidence（编号列表 ≥3 项 / 批量词强信号，保守匹配）。
- `ManusAgentController`：删除 intentRouter 注入、闲聊短路、`casualChat` 方法、
  complexity 阈值与短消息兜底配置；聊天链路改调 OrchestrationRouting，prefaceActivity
  显示「编排路由」原因。回滚开关 `app.agent.single-loop.enabled=false` 保留。
- 删除 `QwenIntentRouter`（含测试）；`IntentRouterConfig` 收敛为 none/jev；
  `application.yml` 删 `short-circuit-enabled`。IntentRouter/IntentDecision/JevIntentRouter
  保留为**复活槽位**（评测平台 InternalEval 仍在用）。
- 新增 `OrchestrationRoutingTest`（4 项：默认单循环/硬规则/并行证据/空安全）。

## 验证

- 定向测试 18/18（Routing 4 + Jev 7 + Workspace 2 + DirectNode 5）；全量回归 145 类，
  失败 3 个：ToolGuardHookTest / PgVectorVectorStoreConfigTest（DashScope 欠费基线）+
  **MediaJobServiceTest（新面孔）**——FK 清理顺序问题（media_job_event 残留挡 DELETE），
  creative 测试基建问题，与本批 agent 改动无关；原基线 MediaJobSchedulerTest 本次通过
  （flaky 证实）。MediaJobServiceTest 待修复（另行处理）。
- 本地真实三例：闲聊 → 单循环 calls=1 ✓；线性工具任务（查时间+写文件）→ 单循环 calls=3 ✓；
  三件事编号列表 → 并行证据命中升级图编排 calls=5 ✓。

## 过程中的事故与修复（已记 docs/bugs/）

1. **JAR 残留孤儿类导致启动失败**：删除 QwenIntentRouter 源文件后，target/classes 里堆积的
   macOS 复制副本（`QwenIntentRouter 3.class` 等，持续再生——某个本机进程在编辑时复制文件）
   被增量打包进 JAR，Spring 扫描崩溃（FileNotFoundException）。修复=`mvn clean package`
   （clean 只动 target/ 构建产物，安全）；**教训：删除/重命名源文件后必须 clean，不能用增量**。
2. python 脚本替换控制器字段时留下孤立 @Resource 注解 → 编译错；修正后通过。

## 遗留与用户操作项

- 本地 `application-local.yml` 的 `app.intent.provider=qwen` 已无对应实现（自动落恒空路由器
  =禁用，行为正确）——建议用户自行改为 none 或删除该键（按规我不动 local 配置）。
- MediaJobServiceTest 的 FK 清理修复（独立小任务）。
- 并行证据正则只认强信号；观察分档比例后再考虑加维度（`agent.orchestration` 计数器）。
- 意图评测集（12 例）仍可经评测平台跑 Jev 槽位；聊天主链路已无意图分类器。

## 注意

- 本轮含 push + 线上部署（用户要求）；前一批（编排三档）为 0.7.28。
- 排查分档：`agent_run.last_node='direct'` = 单循环档；日志 grep「编排路由」。
