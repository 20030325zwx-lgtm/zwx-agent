# 会话摘要：Agent 循环升级 P0——worker 思考阶段流式化（2026-11-07 深夜）

## 需求与方案

用户搁置创作模块回到 agent 模块，先要求梳理技术栈与组成，再要求对比市面 ReAct 循环方案。
对照 Claude Code / OpenAI Agents SDK / LangGraph 得出 4 项差距，按用户确认实施 P0：
**worker 思考阶段流式化**（填平干活阶段空白期）。详见
`docs/design/2026-11-07-agent-loop-upgrade.md`（含差距盘点、P1/P2 路线）。

## 关键事实澄清（对比时的误判修正）

- 最终回答**早已** token 级流式：`AggregatorNode` → `answerChunkSink` → `stream.sendMessage`；
  批 2 闲聊短路也是全程流式。真正空白期在 worker `think()`（阻塞 `.call()`，一轮 10-40s）。
- Spring AI 1.0.0 关闭内置工具执行后 `.stream()` 吐原始 delta；`MessageAggregator`
  只聚合文本/usage **不合并工具调用**（v1.0.0 源码核实）→ 必须自研聚合器。
- advisor 链流式 pass-through：增量能到达消费方，`TokenUsageAdvisor`（Call+Stream 双实现）
  计量不受影响。

## 改动

- 新增 `agent/core/ThinkStreamAggregator`：delta 聚合（文本拼接 + 工具调用「同 id 追加/
  空 id 续写上一条」合并 + usage 累计），输出与 `.call()` 行为等价的 ChatResponse；
  流全空抛异常走既有重试；每轮 think 结束发空串冲刷信号。
- 新增 `agent/graph/ThinkDeltaEvent`（workerKey/agent/text）与 `ThinkDeltaCoalescer`
  （≥120 字符或 250ms 合流；空串=冲刷信号）。
- `ToolCallAgent`：`thinkTextSink` 注入式双路径（null=阻塞原路径，零行为变化）；重试语义
  保留且重试前向 sink 发诚实标记「（模型连接中断，正在重试…）」；`chatSpec` 统一组装并
  防御空系统提示词（`DefaultChatClient.system(null)` 会直接抛 IAE——顺手修掉的既有地雷）。
- `RunContext` 第 10 字段 `thinkDeltaSink` + `withThinkDeltaSink`；`WorkersNode.GraphWorker`
  注入合流器并在 worker 结束时冲刷；`ManusGraphOrchestrator` 开关
  `app.agent.worker-think-stream`（默认开）+ sink → `stream.send("think-delta", json)`。
- 前端 `SuperAgent.vue`：think-delta 监听（按 workerKey 聚合、滚动尾部 400 字）、
  activity/onmessage 时折叠为「思考片段：…」入 activities、interrupted/FAILED/finishStream
  清理；`AgentTrace.vue` 新增 `.think-live` 活区（与"已深度思考"面板同风格）。

## 验证

- 单测 11/11：ThinkStreamAggregatorTest 7 项 + ToolCallAgentStreamThinkTest 4 项
  （含「直调 think() 需 setState(RUNNING) 否则停止检查判真→空流」的坑）。
- 全量回归 144 类：仅 3 个已知基线失败（ToolGuardHookTest / MediaJobSchedulerTest /
  PgVectorVectorStoreConfigTest，DashScope 欠费，与本轮无关）。
- 真实三轮（GPT-5.6 Terra）：后端 `[think-delta]` debug 日志 68 字拆 6 条实时下发
  （250ms 窗生效）；前端三轮轨迹均出现与后端字数精确一致的「思考片段」；完整链路
  （意图→规划→流式思考→工具→质检→返工→交付）行为不变。后端已重打包重启（health ok）。

## 遗留

- think-live 活区只有思考窗口 >采样间隔时肉眼可见（短思考≈直接折叠）；若要「首字即现」
  体验，可把合流窗降到 100ms 或首 delta 立即下发后续合流（未做）。
- P1：maxSteps/5 分钟时限 → token 预算（挂 RunQuotaService）；模型 failover 链
  （DynamicChatModelFactory 加备选切换，中转站 429 场景）。
- P2：编排模式化——直答/单循环/图编排三档，verifier、aggregator 变可选。
- reasoning_content（思维链）展示未做；`getCurrentTime` 被工具守卫「未登记默认拒绝」
  是既有行为（超级智能体工具注册表未含），与本轮无关。

## 注意

- 未提交 git（working tree 有：4 个新增后端类、5 处后端改造、2 个前端文件、
  设计文档、2 个测试类），待用户确认后提交。
- 观测 think-delta：看 debug 日志 `[think-delta]` 或 SSE 事件名 `think-delta`；
  REST 事件端点只对活跃/近期 run 有效（RunStream 完成 120s 后从内存清退）。
- 浏览器自动化调试经验：IAB evaluate 在隔离 JS 世界，window 写入与页面不互通，
  DOM 读取共享；给页面装 EventSource 探针无效，应看后端日志。
- 全量 `mvn test` 跑完 144 类后进程可能卡在退出阶段（非守护线程），报告已完整可
  直接停掉进程，属既有现象。
