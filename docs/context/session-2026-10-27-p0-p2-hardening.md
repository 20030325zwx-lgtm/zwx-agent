# 会话交接：P0–P2 生产化加固批量落地（2026-10-27）

> 本份作为「最新一份摘要」；上一份 `session-2026-10-26-tool-loop-guard.md` 的改动已提交（3ba5044），其第 3 节遗留项中「两仓库未 push 提交」仍有效，其余见本文第 5 节。
>
> 本轮按此前评估的 P0→P2 清单依次规划、实现、测试、提交，共 11 个功能/重构提交。

## 1. 改了什么（按优先级）

**P0-1 评测接入门禁**
- `.github/workflows/ci.yml`：三 job（后端 `mvn test` / 前端 `npm run build` / 意图评测门禁）。
- `evaluation/eval-sets/run_intent_gate.py`：仅意图 12 例、不依赖本地后端，通过数 <11 退出码 1；无 `DASHSCOPE_API_KEY` secret 时跳过。系统提示词与 `QwenIntentRouter` 必须同源维护。

**P0-2 有副作用工具的人工确认（本轮最大改动）**
- 新表 `agent_tool_confirmation`（V26）+ `agent_run.pending_confirmation` JSONB 列。
- `ToolConfirmationService`：注册制（只有图编排 run 启用，单智能体/旧路径不受影响）；默认拦截 `executeTerminalCommand/executeDatabaseQuery/queryExternalDatabase/downloadResource/mcp:*`，文件/PDF 产出不拦截；`app.tools.confirmation.*` 可配。
- 链路：`ToolGuardHook` 抛 `ToolConfirmationRequiredException` → worker 以受控文案收尾 → WorkersNode 叫停兄弟 worker 并写 `PENDING_CONFIRMATION` 图状态 → Verifier/Aggregator 早退（不消耗模型调用）→ 编排器发 SSE `confirmation-required` + 落库、run 记 INTERRUPTED、**跳过 workers 检查点（最后一个检查点保持 planner）** → 用户批准/拒绝（`POST /ai/manus/conversations/{cid}/runs/{rid}/confirmation`）→ 前端调既有 resume → workers 重跑（已成功工具由幂等账本回放，不重复副作用）。
- `BaseAgent.hookRunId`：hook 上下文携带真实 runId（图路径由 WorkersNode 设置）。
- 审计：`GET .../runs/{runId}/tool-calls`（幂等账本只读视图）+ RunInspector 新「工具」页签；前端确认弹窗（ConfirmDialog 复用）+ 暂停横幅。

**P0-3 指标与告警**
- actuator + micrometer-prometheus；**管理端口 8135**（8124/8125 均被占用，见 bug 记录）；`/actuator/**` 在 SecurityConfig 放行（端口不对外发布，指标无用户标识）。
- `AgentMetrics`：run 结果、挂起确认、工具拦截、确认决定、检索空命中、检索降级、意图动作、配额拒绝。
- compose 可选 `--profile monitoring`：prometheus + grafana（预置 Aster 总览面板 10 块）+ backend healthcheck。

**P1-4 每用户配额**：`RunQuotaService`（并发 2 / 每小时 30 / 24h token 200 万，均可配），chat 与 resume 前检查，429 带原因；前端 SSE 客户端解析 429 响应体并直接展示、不重连。

**P1-5 联网引用**：`WebSourceService` 从幂等账本解析搜索（SearchAPI 原始对象/Tavily 两种格式，括号包裹解析）与抓取 URL → `GET .../runs/{runId}/web-sources` → RunInspector 来源页「联网参考」区。

**P1-6 记忆治理**：`MemoryService` 增 listFacts/addFact/updateFact/deleteFact（tenant+user 归属隔离，hash 冲突返回 409）+ `MemoryController`（/ai/memory/facts CRUD）+ 前端记忆管理页（导航「记忆」）。

**P1-7 结构债**：900 行 `AiController` 拆为 `ManusAgentController`/`KnowledgeAdminController`/`McpAdminController`/`SkillAdminController`（**全部路径保持不变**）；删除死代码 `ReReadingAdvisor`、`FileBasedChatMemory`、`ZwxManus`(+测试)、`HelloWorld.vue`。注意 `app.love.vision-model` 与 `LoveKnowledgeReference` 仍被引用，未删。

**P2-8 意图批 2**：短路条件收紧 `intent=闲聊 && needsKnowledge<0.3`（`SHORT_CIRCUIT_KNOWLEDGE_CEILING`）；`casualChat` 单轮直达路径（不建 run、不走向量/联网/图编排，消息照常持久化）；`app.intent.short-circuit-enabled` 默认 true。**重要局限**：本地 52 次 run 全部在 `intent.provider=none` 下产生，无真实观察数据，启用依据是评测门禁 12/12；provider 启用后需看 `agent_intent_decision` 指标复盘。

**P2-9 前端批次 3**：侧栏按今天/7 天/更早分组；首页最近任务流（`GET /ai/manus/runs/recent`）+ run 深链 `/super-agent?conversationId=…&runId=…`；RunHeader「导出」按钮（客户端组装 Markdown 下载）。

**P2-10 健康与保留**：`DoclingHealthIndicator`、`ObjectStorageHealthIndicator`（可选依赖报 UNKNOWN 不拉低整体）；`DataRetentionJob`（**默认关闭**，`app.retention.*`：事件 90 天删除、完结 run 180 天清大字段、工具账本 365 天删除，每日 03:30）。

## 2. 验证结果

- `mvn clean test`：**283 通过，0 失败，1 跳过**（删除 ZwxManusTest 后新增配额 6/联网引用 4/记忆治理 5/指标 2/确认门禁 3/节点早退 2 等）。
- `npm run build` 通过；`mvn clean package -DskipTests` 出包并 `launchctl kickstart -k` 重启。
- 冒烟（全部实测通过）：`/api/health`=ok；`8135/actuator/health` 整体 UP（docling UNKNOWN 不拉低、objectStorage UP）；`/actuator/prometheus` 输出 `agent_run_finished_total`/`agent_intent_decision_total`；真实 run 一次（员工手册迟到规则，knowledgeSearch=true）：SSE 全事件（run/plan/rag-trace/activity/usage/message/[DONE]），run 落库 COMPLETED，token 计量 3392/177/3 次调用；`tool-calls`/`web-sources` 空数组、confirmation 对非挂起 run 409、memory facts 增改删全通过；前端 3000 端口 200。
- 意图门禁本地实测 12/12（qwen-plus）。

## 3. 环境注意事项（重要）

- **管理端口是 8135**：8124 被本机 MCP 子服务 java 进程占用，8125 被 aster-infra 容器 OrbStack 转发占用（隐蔽：Tomcat 能启动但 curl 命中转发 404）。详见 `docs/bugs/bug-2026-10-27-actuator-management-port-collision.txt`。
- 后端改动后仍需 `mvn -DskipTests package`（建议 clean）再 `launchctl kickstart -k gui/$UID/com.zwx.yu-ai-agent.backend`；`application-local.yml` 未做任何改动。
- compose 监控栈：`docker compose --profile monitoring up -d prometheus grafana`（Grafana 默认 admin/admin）。

## 4. 文档同步

- `docs/ROADMAP.md`：10 条状态更新（联网引用/工具确认/指标/批次 3/健康检查等改为已完成或部分完成，下一步改为告警面板、OS 沙箱、逐节点 deadline、观察复盘）。
- `evaluation/eval-sets/README.md`：门禁接入说明 + 批 2 启用记录（含观察期局限）。

## 5. 遗留事项

1. 上一份摘要的遗留项仍有效的：A 档②'/②/③、压缩无损化+评测集、Jev 知识库误判、**两仓库未 push**、测试 run 未清理、执行轨迹图路径未接线、循环防护软干预级。
2. 本轮新增遗留：
   - 确认链路的 OS 级沙箱未做（python3 仍可读宿主文件）；工具级取消依赖挂起机制。
   - 配额的小时口径不统计闲聊短路轮（不建 run）；token 台账不含闲聊轮。
   - 告警面板与阈值告警（Grafana Alerting）未配；模型/MCP 配置面健康检查未做。
   - 意图 provider 启用后需复盘（P2-8 观察期）。
   - 生产环境 `/actuator/**` 匿名放行若管理端口要对外发布必须重审。
   - `docs/design/agent-architecture.md` 较旧（仍描述 love/travel 入口与 AiController），建议后续按新控制器结构修订。
3. 本轮全部改动已提交（11 个提交，master）；未 push。
