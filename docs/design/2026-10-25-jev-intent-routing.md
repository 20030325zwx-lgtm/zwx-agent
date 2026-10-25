# Jev 意图分流层（2026-10-25）

> 状态：批 0（观察模式）实施中。目标：在超级智能体建图之前，用 TypeSafe Jev（System One 决策模型）做一次低成本意图识别，为"自动开 knowledgeSearch/webSearch 开关"和"闲聊短路"攒决策依据。**批 0 不改变任何行为**——只调用、只记录、只在前端展示。

## 1. 背景与动机

当前意图分发是隐式的三处机制（Planner 拆解 / workers function calling / 用户手动勾选 `knowledgeSearch`/`webSearch`）。代价：意图靠用户告诉系统，勾漏了检索不到（2026-10-25 G60 会话中 verifier 曾质检"未读知识库"返工，多烧两轮 LLM）；简单问题也要过完整图。

Jev 是 TypeSafe 的 System One 决策模型（RLCD 训练，返回结构化判断 + 校准概率，非生成式）。选择它而非 qwen 结构化输出的决定性原因：**分流层必须足够便宜才成立**（用 qwen 做前置路由会吃掉短路省下的成本），且**自动开关需要真校准概率**（LLM 自报置信度不可信）。另外 DashScope 配额 2026-10-25 已被 embedding 打出 429，独立厂商反而分摊风险。

## 2. 接口契约（已核实）

- `POST https://api.typesafe.ai/v1/systemone`，`Authorization: Bearer $TYPESAFE_API_KEY`，model `jev-latest`。
- 请求 `{model, state, questions}`；三种题型：`choice`（criteria 为 名称→描述 对象，返回 choice/confidence/probabilities）、`score`（criteria 为有序描述数组，返回加权 score/confidence）、`noul`（criteria 为 {true,false} 描述，返回 0-1 概率）。
- 响应 `{model, answers: {题名: {...}}, usage: {input_tokens 计费, output_tokens 目前免费}}`。
- 官方 intent-routing 模式：choice 意图 + score 复杂度一次并行问，置信 < 0.5 兜底。

## 3. 意图集合与问题设计（state 只放用户消息文本）

```
intent (choice): {
  "知识库问答": "询问私有资料、手册、规范、项目文档中的内容",
  "联网查询":   "需要实时信息、新闻、行情、外部网站数据",
  "工具任务":   "生成文件、处理文档、写代码、多步骤操作",
  "闲聊问答":   "打招呼、常识问答、闲聊，无需资料和工具"
}
complexity (score): ["单轮可答", "需要多步骤", "需要研究分析"]
needs_knowledge (noul): "回答这个问题是否需要检索私有知识库？"
```

## 4. 决策表（阈值待观察期校准）

| 条件 | 批 1 起的动作 | 批 0 行为 |
| --- | --- | --- |
| intent 置信 ≥ 0.7 且 = 知识库问答（或 needs_knowledge ≥ 0.8） | 自动开 knowledgeSearch | 仅记录+展示 |
| intent 置信 ≥ 0.7 且 = 联网查询 | 自动开 webSearch | 仅记录+展示 |
| intent = 闲聊问答 且 complexity ≤ 0.5 且 intent 置信 ≥ 0.85 | 短路候选（批 2，需评测门禁） | 仅记录+展示 |
| 置信 0.5~0.7 | 只自动开检索类开关 | 仅记录+展示 |
| 置信 < 0.5 | 完全回退现状 | 仅记录+展示 |

## 5. 工程落点

- 新包 `platform/intent/`：`IntentRouter` 接口（`Optional<IntentDecision> classify(String message)`）+ **两个实现**：`JevIntentRouter`（TypeSafe System One，需 Key）与 `QwenIntentRouter`（DashScope 兼容模式对照实现，复用现有 Key；LLM 自报置信非校准）。`IntentRouterConfig` 按 `app.intent.provider`（none/jev/qwen）选择 primary 暴露。**任何异常（禁用/无 Key/超时/解析失败）返回 empty，调用方按现状行为执行**——降级即现状。
- 配置 `app.intent.*`：`provider`（none/jev/qwen）；`jev.*`：enabled/base-url/api-key（`${TYPESAFE_API_KEY:}`，留空即实际禁用）/model（jev-latest）/timeout-ms（1500）；`qwen.*`：base-url/model/timeout-ms（2500），api-key 复用 `spring.ai.dashscope.api-key`。路由是基础设施，不进用户模型配置层。
- 可观测：决策以 `activity` 事件（phase=plan，agent=意图识别）推给前端，格式如"意图：知识库问答（置信 0.92）· 复杂度 0.4 · 建议自动开启知识库检索 → 观察模式不改变行为"；后端同步打 INFO 日志攒对照数据。通道：`ManusRunRequest` 新增可空 `prefaceActivity` 字段，由 orchestrator 在 `run` 事件后发出。
- 决策建议计算为纯函数 `recommend(decision)`，批 1 直接消费。
- 已知账号约束：该 DashScope 账号的 qwen-turbo 模型被限流（429），意图路由本地配置使用 qwen-plus（实测 0.5-1.3s）。

## 6. 分批

- **批 0（已实施 2026-10-25）**：观察模式，零行为变更，攒对照数据。
- **批 1（已实施 2026-10-26）**：classify 前移到检索/工具装配之前，`applyTo()` 反哺 knowledgeSearch/webSearch。**规则：只开不关**——用户已勾选视为"维持"，未勾选而意图命中则自动打开并在活动事件中明示（"已自动开启知识库检索（原为关闭）"）；短路候选与低置信一律维持用户开关。e2e：不勾知识库问"先维信息员工年假"（意图 0.95）→ 检索自动开启、命中员工手册 PDF、答案带引用；闲聊消息 → 检索未误开。
- **批 2（待做）**：闲聊短路，需评测门禁（短路回答 vs 图回答质量对比）。
- **批 3（可选）**：noul 高风险判断接入 ToolGuard 前置门控。

## 7. 风险与边界

- **数据出域**：state 只放用户消息文本，绝不放知识库内容、租户数据或历史会话；密钥照常走 application-local.yml（不入 Git）。
- **新厂商风险**：Jev 2026-09 面世，SLA/限流/定价无文档承诺；降级路径必须做到"任何异常 = 现状行为"；OpenRouter 端点可作备选。
- **中文 criteria 质量**决定识别上限，观察期用真实消息校准措辞。
- 换模型/换厂商：`IntentRouter` 接口隔离，批 0 攒的数据就是未来本地分类器或 qwen-turbo 对照的评测集。
