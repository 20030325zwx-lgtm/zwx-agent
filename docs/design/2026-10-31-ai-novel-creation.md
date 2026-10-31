# AI 小说创作 v1：有状态的长篇创作层（2026-10-31）

## 1. 定位：创作层与工作流的分界

用户提出的概念性问题：项目创作台里的「用工作流创作」和画布直接点运行有什么区别？纯图片生成是否工作流就够了？

结论（本设计的前提）：

- **工作流 = 无状态单次加工管线**。画布运行与「用工作流创作」入口底层完全相同（同一引擎、同一 `POST /ai/workflows/{key}/runs`）；入口差异只有项目绑定（产物归集）与表单语境。纯图片生成，画布直接跑就够。
- **创作项目 = 有状态容器**。素材库、作品设定、章节库随使用不断累积；工作流每次运行**读项目状态 → 加工 → 写回状态**。
- AI 小说把这个状态层真正建立起来：每写一章 = 一次工作流运行，运行读取设定/目录/前章概要，生成后写回章节库；章节确认后概要进入后续章节上下文，**状态随创作增长**——这是画布单跑没有的东西。AI 漫剧将来复用同一状态层（章节 → 分幕 → 分镜图）。

## 2. 数据模型（V13__novel_chapter.sql）

- `novel_chapter`：项目章节库。`(project_id, tenant_id, user_id)` 组合外键保证归属；`(project_id, chapter_number)` 唯一；`status ∈ {DRAFT, CONFIRMED}`；`source_run_id` 支撑工作流幂等入库；`plot_direction/content/summary/word_count` 记录剧情走向、正文、概要与字数。
- `creative_project.settings_json JSONB`：项目级设定（长篇创作共用），小说约定 `{characters, worldview, style}` 三个自由文本块。
- 种子模板 `novel-chapter` v1：`novel_context → llm_text(正文，含 system) → llm_text(概要) → novel_chapter_save`；运行输入 `plotDirection`（必填）/ `title`（可选）。

## 3. 工作流引擎扩展

| 项 | 说明 |
| --- | --- |
| `novel_context` 节点 | 读绑定项目的设定 + 全部章节目录（含状态）+ 近 N 章概要（`summaryCount`，默认 3）+ 下一章序号，输出**纯文本上下文块**供 llm_text 整体引用。未绑定项目报错。 |
| `novel_chapter_save` 节点 | `content` 必填；标题空则「第N章」；按 `source_run_id` 幂等 upsert（崩溃重跑不产生重复章节）；status=DRAFT；输出 `{chapterId, chapterNumber, wordCount}`。 |
| `MAX_OUTPUT_CHARS` 4000 → 100_000 | 节点输出持久化到 steps JSONB 的上限。同一次执行内下游引用本不截断，但**跨等待/续跑**时输出从 steps 重载，4000 会截断章节正文（评审 R06 输出截断项的针对性修复）。运行时间线 UI 只展示 message，不受影响。 |
| `llm_text` 增加 `system` 参数 | 可选；非空时 `.system(...)`，向后兼容。 |
| 画布 | palette/默认参数/帮助文案新增两节点（WorkflowEditor.vue）。 |

## 4. 后端 novel 域（`creative/novel` + NovelController）

- `NovelChapterService`：章节列表/编辑/确认（DRAFT→CONFIRMED，正文为空拒绝）/删草稿（CONFIRMED 不可删）；`saveFromRun` 供工作流节点调用（项目必须 ACTIVE，章号 max+1，同 run 幂等）。
- `NovelPlanningService.suggest`：读设定 + 目录 + 近 3 章概要 → `PURPOSE_CHAT`（中转站 chat 模型）→ 3 组 `{title, direction}` 候选；JSON 解析容忍代码围栏/杂讯，缺字段跳过，最多 3 组。
- `CreativeProjectService.settingsOf/updateSettings`：项目设定读写（浅合并、已知键、单值 ≤6000 字符）。
- `NovelController`（`/ai/creative/projects/{projectId}/novel/*`）：chapters CRUD + confirm、settings GET/PUT、plot-suggestions POST。
- `CreativeKind.enabled()` 放行 `NOVEL`。

## 5. 前端（小说项目体验）

- 项目页（CreativeProject.vue）：`kind==='NOVEL'` 时「创作」tab 换成「章节」tab（默认激活，图片 studio 面板不出现）：
  - 章节列表（序号/标题/状态徽标/字数/更新时间）→ 展开编辑器（标题/正文/概要可编辑保存；确认入库带确认弹窗；删草稿仅限 DRAFT）；
  - 作品设定折叠区（人物/世界观/文风，保存后注入后续章节）；
  - 「写新章节」弹窗：标题（可空自动编号）+ 剧情走向 + **AI 建议剧情走向**（3 张候选卡点击采纳）+ 模板下拉（默认 novel-chapter）→ 发起后跳工作流 tab。
- 创作首页：小说创作 chip 与新建弹窗 kindOptions 开放 NOVEL。
- 单章生成计费 = chat 模型 tokens（中转站，无生图费用）；剧情建议同链路。

## 6. 验证记录（2026-10-31）

- `NovelRulesTest` 4 项全绿（字数/建议解析容错/设定块）。
- E2E（浏览器，项目「星槎航海录」）：
  1. 新建 NOVEL 项目 → 章节 tab 默认激活、「创作」tab 正确隐藏；
  2. 作品设定保存成功（toast 确认）；
  3. 写第 1 章：AI 建议 3 组候选（贴合人物/世界观）→ 采纳「星坠前夜」→ 运行 SUCCEEDED（上下文节点报告「既往 0 章，本章为第 1 章」）→ 草稿 1753 字入库、概要生成；
  4. 确认入库 DRAFT→CONFIRMED；
  5. 写第 2 章：AI 建议明显承接第 1 章概要（罗盘/星图名字/辅星逆轨全部呼应）→ 采纳「罗盘灼痕」→ 运行报告「**既往 1 章，本章为第 2 章**」→ 草稿 1999 字，正文开头精准衔接所采走向。
- V13 迁移应用核对（表结构 + 种子模板）；前端 build 通过；后端 package 通过。

## 7. 边界与后续演进

- 章节号创建后不可重排；CONFIRMED 不可删除；同一运行重复入库更新原草稿。
- 生成是概率性输出：概要与正文需人工确认，「已确认」代表作者采纳，不代表事实/设定零冲突（正文可继续编辑）。
- 后续（未排期，按需求启动）：漫剧（复用状态层 + 分幕/分镜图节点）、一键连写多章（顺序多次运行）、结构化角色卡、章节版本历史、生成中章节的运行态展示、桌面端随下次打包携带。
- 专家评审第二批中的模板导出导入对小说模板同样适用（种子已入库，本地库不再单点）。
