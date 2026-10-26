# AI Agent 开发岗题库（应用/工程落地向）

> 定位：**AI 应用开发 / Agent 开发工程师**，不是算法后训练岗。所有答案口径统一为
> 「选型动机 → 失败模式 → 工程兜底 → 度量指标」。
> 底层题（Transformer / RLHF / LoRA / 量化）集中在 `04-java-backend-appendix.md`，标注「进阶选看」。

## 与既有材料的关系

- [`../agent-interview-notes.md`](../agent-interview-notes.md)：**项目深挖专用**。里面是结合 Aster 源码回答的 12 道题（架构链路、四能力落地、权限审计、Shell 防护、Prompt Injection、SSRF、数据泄露、线程泄漏、线程池、取消与超时、安全边界速记、已知缺口）。
- 本目录：**通用考点题库**。八股 + 系统设计 + 手撕清单。每题末尾的「Aster 挂钩」告诉你这道题该怎么用自己的项目来举证，以及哪部分仍是不该吹的缺口。
- 一句话分工：被问「你项目怎么做的」→ 看 notes；被问「这个概念/选型怎么回事」→ 看这里。

> 注意：`../agent-interview-notes.md` 末尾「源码索引」里的部分包路径在仓库重构后已失效。
> 以本目录引用的实际路径为准：`agent/graph/`、`agent/hook/`、`capability/tools/`、`capability/mcp/`、`capability/skills/`、`chat/memory/`、`knowledge/retrieval/`、`agent/observability/`、`evaluation/eval-sets/`。

## 文件与题量

| 文件 | 主题 | 编号前缀 | 题量 |
| --- | --- | --- | --- |
| [`01-agent-core.md`](01-agent-core.md) | Agent 概念与架构、推理范式、规划与任务拆分、Multi-Agent、Harness、自进化 | Q1.x | 30 |
| [`02-tools-memory-rag.md`](02-tools-memory-rag.md) | Function Calling、MCP/Skills、工具路由与失败处理、Memory、上下文工程、RAG、存储与性能 | Q2.x | 44 |
| [`03-eval-security-design.md`](03-eval-security-design.md) | 评估与可观测、线上排查、安全与权限、10 道系统设计压轴、手撕清单 | Q3.x | 34 |
| [`04-java-backend-appendix.md`](04-java-backend-appendix.md) | 配套后端八股（Redis/缓存/并发/流式/JVM）+ 底层进阶（选看） | Q4.x | 14 |

合计 **122 题**，全部带 30–45 秒口述答案、追问预案、工程兜底要点和 Aster 挂钩。

## 使用方法

1. **先扫答案，再盖住答案自答**。每题的「口述答案」控制在 30–45 秒，超时就删细节，别背长句。
2. 「追问预案」是面试官第二、三问的位置。答完主答案主动抛出一个追问点，比被动等问更好——它显示你知道边界。
3. 「Aster 挂钩」不要逐字背，记住**一个具体类名 + 一个具体取舍**即可。面试官对「我用了 ToolGuardHook 做集中预检，但未知工具默认拒绝会误伤新接入工具，所以我加了显式 allowlist 开关」这种话的记忆度，远高于对概念的复述。
4. 弱项标记法：在题号后加 `!!`（不会）或 `~`（含糊）。复习只读带标记的行：
   ```sh
   grep -n "^### Q.*!!" 0*.md
   ```
   会了就删标记。**每次面试后 30 分钟内更新一次**，把真实被问倒的题加回来。

## 7 天冲刺路线

| 天 | 内容 | 产出 |
| --- | --- | --- |
| D1 | `01-agent-core.md` 全刷 + 标弱项 | 能不看稿讲清 ReAct/Plan-and-Execute/Reflection 选型 |
| D2 | `02-tools-memory-rag.md` 前半（工具与 MCP） | 手写一张 FC → MCP → Skills 的对比表 |
| D3 | `02-tools-memory-rag.md` 后半（Memory + RAG） | 画出自己项目的 RAG 全链路图，标出每段可优化点 |
| D4 | `03-eval-security-design.md` 评估 + 安全 | 准备好「我怎么量化 Agent 性能」的 3 个数字 |
| D5 | 系统设计压轴题，每题口述 5 分钟并录音回听 | 形成自己的分层模板，避免每题重新组织结构 |
| D6 | `../agent-interview-notes.md` + Aster 挂钩串讲 | 1 分钟项目介绍 + 3 分钟架构 + 2 分钟踩坑 |
| D7 | 只刷带 `!!` / `~` 的题 + `04` 附录扫读 | 弱项清零，后端八股不漏底 |

## 面试前一小时的三句自检

- 我能不能一句话说清「Agent 比 LLM 多了什么，以及为什么不是所有场景都该上 Agent」？
- 我讲的每个能力（记忆/规划/工具/验收），有没有对应的失败模式和我做过的兜底？
- 我有没有把「应用层校验」错说成「操作系统级沙箱」，把「内存 checkpoint」错说成「持久化恢复」？
