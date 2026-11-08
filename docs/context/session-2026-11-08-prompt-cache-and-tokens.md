# 会话摘要：prompt cache 前缀治理 + token 用量页上可见（2026-11-08 傍晚）

## 需求

用户两条要求：①页面上直接显示每次消耗的 token；②提高 prompt 缓存命中率。

## 诊断与修复

### 缓存命中率的最大杀手（已修）
历史对话此前被拼成一段文本（"用户: …\n助手: …"）塞进 user 消息——每轮整段变化，
**跨轮 prompt 前缀完全不稳定，供应商 prompt cache 命中率恒为 0**。
修复：历史改为**结构化消息列表**全链路传递——controller 两处 + InternalEval 的
`getRecentMessages` 映射为 Message（USER→UserMessage / 其余→AssistantMessage，过滤空与当轮），
`ManusRunRequest.historyTurns` → state HISTORY → DirectNode 在 run 前预填 worker 消息列表
（append-only 前缀：system + 工具 schema + 历史轮次跨轮逐字稳定，新输入是最后一条 user）。
附带清理：agent_run.history_context 列不再冗余写入（历史实时查消息表）；定时任务删无用查询。
稳定前缀审计：系统提示词静态 ✓、工具 schema 同 run 顺序稳定 ✓、think 重试标记只进 SSE 不进历史 ✓。

### Token 可见化 + 缓存命中采集
- `TokenUsageAdvisor` 从 native usage 提取缓存命中（三种形态：OpenAI
  `prompt_tokens_details.cached_tokens` / DashScope `input_tokens_details` /
  DeepSeek `prompt_cache_hit_tokens`）；`CallSample` 加 cachedTokens 组件（record 第 9 参，
  record 不能有实例字段——编译教训）；usageJson / usage SSE 事件携带。
- 前端：usage 事件同时写入 answer 对象；**AgentTrace 状态行新增 token 徽标**
  （`↑提示 ↓生成 tokens · 缓存 N（X%）`，缓存为 0 优雅隐藏）；RunInspector 用量 tab 加缓存命中行。

## 验证

- TokenUsageServiceTest 4/4 + 定向 16/16；前端 build 通过。
- 本地真实两轮对话：第二轮 prompt=21173（结构化历史已进 prompt ✓）、
  usageJson 带 cachedTokens 字段 ✓、cachedTokens=0——**本地中转站（KKAI）实测未透传缓存字段**，
  显示层优雅降级；切官方 OpenAI/DeepSeek/通义供应商时自动生效。
- 线上 0.7.31 发布（backend+frontend，资产哈希一致，公网健康通过）。

## 遗留

- 中转站透传缓存字段不可控：要看到真实命中率数字，需供应商侧支持（官方 API 或确认 KKAI
  某模型透传）。采集/显示链路已就绪，数据来了即显示。
- MemoryCompressionHook 触发时重写历史会击穿缓存（压缩本身必要，接受）；
  触发阈值可后续按「缓存收益 vs 上下文膨胀」调优。
- 前端徽标在 spawn 子代理场景显示的是整 run 汇总（usage 事件为 run 级），per-phase 分解
  在检查器用量 tab 已有。
