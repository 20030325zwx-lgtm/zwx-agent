# 方案 03：流式链路可靠性

## 1. 目标

用户在弱网、刷新页面、切换后台时：不丢消息、不白烧 token、界面与数据库最终一致。完成后：

- 任何中断路径（cancel/error/超时/网关断开）下，用户消息与已生成的部分回答都落库。
- 客户端断开后，服务端在当前 LLM 调用边界停止继续消耗 token。
- 前端断线可恢复：不删除本轮消息，提供恢复/重试入口，刷新后能取回半截回答。

## 2. 现状问题（审计证据）

| 问题 | 位置 |
| --- | --- |
| 保存只挂 `doOnComplete`：客户端断开触发 cancel，USER+ASSISTANT 整轮不落库（RAG 检索成本已花但结果丢弃） | `app/LoveApp.java:144-150、174-181`、`app/TravelPlannerApp.java:67-71` |
| manus 用户消息先行落库，但异常路径不调用 `completionHandler` → 回答永久丢失 | `AiController.java:438、447-448`、`agent/BaseAgent.java:215-223` |
| 服务端无取消注册：断开后当前步骤的完整 LLM 调用照常烧完；`cancel(true)` 对 LLM HTTP 调用不敏感 | `BaseAgent.java:124、215、230-244、268-284` |
| travel 断开时 `deleteRun` 删掉已记录的执行轨迹 | `AiController.java:550-553` |
| 前端 `onerror` 直接 close 并 splice 删除本轮消息；无重连、无 Last-Event-ID | `zwx-agent-frontend/src/api/index.js:42-45`、`views/LoveMaster.vue:237-247`、`views/TravelPlanner.vue:121-129` |
| 无请求幂等：双击发送/重试会重复触发整轮生成 | 各 chat 端点 |

## 3. 方案设计

### 阶段 1：服务端兜底落库（先保证不丢）

1. 统一"整轮写入"语义改造：**用户消息在开始生成前先落库**（状态 `IN_PROGRESS`），流结束时在同一事务内更新 ASSISTANT 回复并标记 `COMPLETED`。
   - `LoveApp` 流式链路：`doFinally` 中区分 `onComplete` / `onError` / `cancel`；cancel 与 error 均保存已聚合的部分回答，标记 `interrupted=true`（新增消息状态字段）。
   - `TravelPlannerApp`、manus 的 `saveCompletedTurn` 同样改到 `doFinally` / `SseEmitter` 回调兜底。
2. `love_chat_message`、`agent_conversation_message` 增加 `status`（`IN_PROGRESS`/`COMPLETED`/`INTERRUPTED`）迁移；历史读取接口照常返回（前端按状态渲染"已中断"标记）。
3. RAG 引用/trace 写入随之挪进兜底路径：中断轮也保存已产生的 `rag_trace`（含降级原因），便于排障。
4. travel 断开时**保留**执行轨迹，只把 run 状态置为 `INTERRUPTED`（新增状态），`deleteRun` 仅在用户显式删除会话时级联执行。

### 阶段 2：取消语义（不白烧 token）

1. 每条 SSE 连接建立时注册取消回调：`emitter.onCompletion/onTimeout/onError` + Reactor `Disposable`。
2. 取消动作：
   - `LoveApp`：`Flux` 的 `Disposable.dispose()`，中断与 DashScope 的流式连接（WebClient 底层可响应取消）。
   - manus：`BaseAgent` 已有 `activeStep`/`stopReason` 机制，新增 `requestStop()` 由 SSE 取消回调触发；步循环在每次 `step()` 边界检查停止标记——保证最多浪费当前一步，而非当前一步+后续全部。
3. 取消后仍执行阶段 1 的部分落库。
4. 明确不追求的：不中断已发出的单次 LLM HTTP 请求的中间过程（DashScope 不支持），目标是"调用边界及时止损"。

### 阶段 3：断线恢复

1. 服务端 SSE 事件增加单调递增 `id:`（每轮从 0），前端记录最后收到的事件 id。
2. 新增恢复接口：`GET /love_app/conversations/{id}/messages?afterMessageId=...`（以及 travel/manus 对应接口），返回某轮的最终消息、visionAnalysis、references、trace——前端刷新/重连后用它补齐，而不是 splice 删除。
3. manus 执行事件已有 `runId + sequence`，新增 `GET /manus/runs/{runId}/events` 供重连后重建执行时间线（travel 已有类似接口，抽成共用）。

### 阶段 4：前端改造

1. `api/index.js` 的 `onerror` 不再触发 `cancelActiveStream` 的 splice 逻辑；改为：
   - 保留本轮消息与半截回答，UI 置为"连接中断"态。
   - 指数退避自动重连（1s/2s/4s，最多 3 次），重连成功后调用恢复接口补齐。
   - 重连失败显示"恢复"按钮（手动触发恢复接口）。
2. EventSource 改为 `fetch` + `ReadableStream` 解析（顺带解决方案 01 的 SSE 鉴权头问题），支持 `AbortController` 主动取消。
3. 发送幂等：客户端每轮生成 `clientRequestId`（UUID），发送前先查本地 pending；后端对同一 `conversationId + clientRequestId` 的重复请求直接返回已有轮次（消息表加唯一索引）。

## 4. 交付拆分

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| 批次 1 | doFinally 兜底 + status 字段 | 断网/刷新后重进会话，本轮消息存在且标"已中断" |
| 批次 2 | 取消语义（Reactor dispose + requestStop） | 断开后服务端日志显示在调用边界停止；token 消耗止于当前步 |
| 批次 3 | SSE 事件 id + 恢复接口 | 刷新页面后内容完整恢复 |
| 批次 4 | 前端重连 + 幂等 | 弱网模拟（Chrome throttling）下对话可恢复；双击发送只产生一轮 |

## 5. 风险与决策点

- `interrupted` 部分回答进入上下文窗口：`getRecentMessages` 对 `INTERRUPTED` 消息标注"（回答被中断）"再入上下文，避免模型把半截话当完整结论。
- 双写过渡：改造期间保留 `doOnComplete` 原路径，`doFinally` 兜底按消息状态幂等（已 `COMPLETED` 不再覆盖）。
- fetch-SSE 改造涉及三个页面，建议先抽公共 `sseFetch()` 工具再逐页切换。
