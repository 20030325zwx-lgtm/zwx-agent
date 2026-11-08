# 意图路由 v2：信号分层，删除前置 LLM 分类器（2026-11-08）

## 决策依据

用户拍板「qwen 模型不再参与意图识别」并要求参考 deepseek-harness 与 ZCode 重新设计。调研结论：

- **deepseek-harness（本地源码核实）**：根本没有任务意图分类器——代码里的 "intent" 是 UI
  surface 渲染意图（`SurfaceIntent`，消息如何追加/替换到面板），与任务路由无关。主模型在
  上下文里自己处理一切，没有前置分类调用。
- **ZCode**：同样没有。路由信号是用户显式模式（权限五档/计划模式/自动化）+ 便宜确定性匹配
  （skills 按 description 触发）+ 主模型自路由。
- **对照本项目的教训**：qwen 分类器是每次消息前的一次额外串行 LLM 调用（350ms+），且依赖
  DashScope 免费额度（限流 400/429 为常态，实际长期失效）；复杂度语义（0-2）与编排分档真正
  需要的判据（可否并行）不重合。

## 设计：信号从贵到便宜分层

```
层1 用户显式（最高优先，零成本）：
    项目工作区请求 → 图编排；带图 → 图编排
层2 确定性匹配（微秒级，零成本）：
    并行证据 → 图编排（编号列表 ≥3 项 / 批量词：批量|逐个|挨个|每个都|每张|分别对|分别生|分别查|分别测）
层3 默认：单循环（DirectNode）
    ——直答不再是前置分类，而是单循环的自然结果（模型不调工具即结束，1 次调用）
回退：app.agent.single-loop.enabled=false → 全量图编排（零代码回滚）
```

原则（写进 `OrchestrationRouting` 类注释）：
- 图编排的准入条件是「存在可并行的子任务单元」，不是「复杂」——复杂但线性的任务拆开只会丢上下文
- 保守匹配：误放进图编排的代价只是多几次模型调用（结果仍正确），误扣留单循环的代价是串行变慢
- 复活槽位：独立分类器接口（IntentRouter + JevIntentRouter + IntentDecision）保留、不再接入聊天
  主链路，仅供评测平台（InternalEvalController）与未来分档器复活使用

## 改动清单

- 删除 `QwenIntentRouter`（含测试）——qwen 退出意图识别（用户指令）。
- `IntentRouterConfig`：provider 选型收敛为 none/jev；`application.yml` 同步注释并删除
  `short-circuit-enabled`。
- 新增 `OrchestrationRouting`（platform/intent）：`decide(workspace, hasImages, message, enabled)`
  + `hasParallelEvidence`（强信号保守匹配）。
- `ManusAgentController`：删除 intentRouter 注入、闲聊短路、`casualChat` 方法与旧复杂度阈值；
  聊天链路改调 `OrchestrationRouting.decide`，prefaceActivity 透明展示分档原因
  （「编排路由」而非「意图识别」）。
- 保留：单循环开关 `app.agent.single-loop.enabled`；删除 `max-complexity`/`fallback-short-message`/
  `max-chars`（v1 意图路由附属配置，随分类器一起退役）。

## 验证（本地真实三例）

| 输入 | 路由 | 结果 |
|---|---|---|
| 「你好，简单介绍一下你自己」 | 单循环 | COMPLETED / direct / llm_calls=1 |
| 「查当前时间并写 txt 文件」 | 单循环 | COMPLETED / direct / llm_calls=3 |
| 三件事编号列表 | 图编排（并行证据命中） | COMPLETED / aggregator / llm_calls=5 |

`OrchestrationRoutingTest` 4/4 + 既有 Jev/Workspace 测试全绿；全量回归见会话摘要。

## 已知边界与后续

- 并行证据的正则只认强信号，复杂任务的隐性并行（"深度调研并对比"）不会被升级——按
  Anthropic 经验这是可接受的默认（串行能完成，只是慢）；观察到真实痛点再加维度。
- 本地 `application-local.yml` 的 `app.intent.provider=qwen` 现无对应实现，自动落恒空路由器
  （=禁用，行为正确）；建议用户自行改为 none 或删除该键。
- 短消息/复杂度启发式随分类器退役，不再有「前置分类失败」的路径——路由是纯函数，永远可用。
