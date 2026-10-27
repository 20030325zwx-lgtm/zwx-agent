# Jev 意图分流层（2026-10-25）

> 状态：已实施，并于 2026-10-27 两次调整。知识库与联网检索完全由用户开关控制；当前意图层只识别工作任务和闲聊问答，不改写任何检索开关，联网工具在开关开启时是否真正使用由编排提示词决定。

## 1. 背景与动机

当前意图分发由 Planner 拆解、workers function calling 和用户开关共同完成。意图识别只做粗粒度执行分流；`knowledgeSearch` 是明确的用户选择，不由分类模型推断。

Jev 是 TypeSafe 的 System One 决策模型（RLCD 训练，返回结构化判断 + 校准概率，非生成式）。选择它而非 qwen 结构化输出的决定性原因：**分流层必须足够便宜才成立**（用 qwen 做前置路由会吃掉短路省下的成本），且**自动开关需要真校准概率**（LLM 自报置信度不可信）。另外 DashScope 配额 2026-10-25 已被 embedding 打出 429，独立厂商反而分摊风险。

## 2. 接口契约（已核实）

- `POST https://api.typesafe.ai/v1/systemone`，`Authorization: Bearer $TYPESAFE_API_KEY`，model `jev-latest`。
- 请求 `{model, state, questions}`；三种题型：`choice`（criteria 为 名称→描述 对象，返回 choice/confidence/probabilities）、`score`（criteria 为有序描述数组，返回加权 score/confidence）、`noul`（criteria 为 {true,false} 描述，返回 0-1 概率）。
- 响应 `{model, answers: {题名: {...}}, usage: {input_tokens 计费, output_tokens 目前免费}}`。
- 官方 intent-routing 模式：choice 意图 + score 复杂度一次并行问，置信 < 0.5 兜底。

## 3. 意图集合与问题设计（state 只放用户消息文本）

```
intent (choice): {
  "工作任务":   "读取或分析资料、查找公司制度流程或文档内容、操作项目、生成或处理文件、写代码、多步骤操作",
  "闲聊问答":   "打招呼、常识问答、闲聊，无需执行任务或查阅资料；是否需要联网不由意图判断"
}
complexity (score): ["单轮可答", "需要多步骤", "需要研究分析"]
```

联网不再作为意图分类：是否联网搜索由用户 `webSearch` 开关决定（开关关闭时装配层移除联网工具、Planner 提示词明示不可联网），开关开启时由编排提示词按任务自行决定是否真正搜索。

## 4. 决策表（阈值待观察期校准）

| 条件 | 批 1 起的动作 | 批 0 行为 |
| --- | --- | --- |
| intent = 闲聊问答 且 complexity ≤ 0.5 且 intent 置信 ≥ 0.85 | 短路候选（批 2，需评测门禁） | 仅记录+展示 |
| intent = 工作任务 | 进入正常编排 | 仅记录+展示 |
| 其他或低置信 | 完全回退正常编排 | 仅记录+展示 |

`knowledgeSearch` 与 `webSearch` 在所有分支中都保持用户传入值，不受意图识别结果影响。

## 5. 工程落点

- 新包 `platform/intent/`：`IntentRouter` 接口（`Optional<IntentDecision> classify(String message)`）+ **两个实现**：`JevIntentRouter`（TypeSafe System One，需 Key）与 `QwenIntentRouter`（DashScope 兼容模式对照实现，复用现有 Key；LLM 自报置信非校准）。`IntentRouterConfig` 按 `app.intent.provider`（none/jev/qwen）选择 primary 暴露。**任何异常（禁用/无 Key/超时/解析失败）返回 empty，调用方按现状行为执行**——降级即现状。
- 配置 `app.intent.*`：`provider`（none/jev/qwen）；`jev.*`：enabled/base-url/api-key（`${TYPESAFE_API_KEY:}`，留空即实际禁用）/model（jev-latest）/timeout-ms（1500）；`qwen.*`：base-url/model/timeout-ms（2500），api-key 复用 `spring.ai.dashscope.api-key`。路由是基础设施，不进用户模型配置层。
- 可观测：决策以 `activity` 事件（phase=plan，agent=意图识别）推给前端，格式如"意图：工作任务（置信 0.92）· 复杂度 0.4 · 维持用户开关"；后端同步打 INFO 日志。通道：`ManusRunRequest` 的可空 `prefaceActivity` 字段，由 orchestrator 在 `run` 事件后发出。
- 决策建议计算为纯函数 `recommend(decision)`，批 1 直接消费。
- 已知账号约束：该 DashScope 账号的 qwen-turbo 模型被限流（429），意图路由本地配置使用 qwen-plus（实测 0.5-1.3s）。

## 6. 分批

- **批 1（已实施 2026-10-26）**：classify 前移到检索/工具装配之前，`applyTo()` 反哺 knowledgeSearch/webSearch。**规则：只开不关**——用户已勾选视为"维持"，未勾选而意图命中则自动打开并在活动事件中明示（"已自动开启知识库检索（原为关闭）"）；短路候选与低置信一律维持用户开关。e2e：不勾知识库问"先维信息员工年假"（意图 0.95）→ 检索自动开启、命中员工手册 PDF、答案带引用；闲聊消息 → 检索未误开。
- **批 1.5（2026-10-26 深夜，按用户反馈修正）**：
  1. **图片消息不再强制检索**（原"强制开启"规则被用户否决——随便问一张图片不该跑无意义检索）：图片消息与文本同规则按意图触发，随便问不检索、由模型基于视觉分析直接作答；
  2. **worker 知识约束从二值改分级**（核心）：原"必须且仅依据资料…不支持时说无法确认"会把模型锁死在检索结果上。新约束：资料优先并引用 → 未覆盖时区分**通用知识/图片内容/可推理问题（基于自身知识继续回答，注明来源不冒用引用）**与**公司内部事实/制度（如实说明无规定、建议向部门确认，不编造）**→ 任何情况不草草结束。e2e：差旅补贴（语料实际覆盖，命中手册#72 带引用）、办公区宠物规定（诚实"无明文规定"，不编造）、图片随便问（不检索+视觉作答）、年假回归（引用不变）。
- **批 2（待做）**：闲聊短路，门禁条件——意图用例全过（r2 基线 12/12 已达成）+ 短路仅作用于 `intent=闲聊问答 && needsKnowledge < 0.3`（常识性问题若语料覆盖，判知识库问答并走检索，见 intent-011 重评）；上线前仍需短路回答 vs 图回答的质量抽查。
- **批 3（可选）**：noul 高风险判断接入 ToolGuard 前置门控。

### 2026-10-27 决策调整

- 删除“知识库问答”意图和 `needs_knowledge` 问题。
- “工具任务”更名为覆盖面更广的“工作任务”。
- 删除 `AUTO_KNOWLEDGE` 和 `AUTO_WEB`，知识库与联网均只服从用户开关。
- 删除“联网查询”意图：联网搜索由用户开关 + 编排提示词共同决定，意图层不再判断联网需求。天气、金价类单轮提问归入闲聊问答（未开联网开关时直接作答、如实说明无实时数据），外部调研类归入工作任务。
- 历史批次记录描述当时行为，不再代表当前运行规则。

## 7. 风险与边界

- **数据出域**：state 只放用户消息文本，绝不放知识库内容、租户数据或历史会话；密钥照常走 application-local.yml（不入 Git）。
- **新厂商风险**：Jev 2026-09 面世，SLA/限流/定价无文档承诺；降级路径必须做到"任何异常 = 现状行为"；OpenRouter 端点可作备选。
- **中文 criteria 质量**决定识别上限，观察期用真实消息校准措辞。
- 换模型/换厂商：`IntentRouter` 接口隔离，批 0 攒的数据就是未来本地分类器或 qwen-turbo 对照的评测集。
