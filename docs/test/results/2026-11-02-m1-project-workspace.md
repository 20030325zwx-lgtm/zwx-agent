# M1 短剧项目工作区：验收证据

日期：2026-11-02。基线：`0a8a97f` + 本批改动。**真实生成计费：0 次**（全部用已有候选与已选片验证，未触发生成）。

## 构建

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 前端 | `npm run build` | ✅ `✓ built in 16.46s`（仅既有 chunk 体积警告） |
| 后端 | `mvn -DskipTests package` | ✅ JAR 已更新，启动时自动执行 V19 |

## 迁移

`V19__creative_workspace_indexes.sql` 在本地服务启动时应用成功：
`Migrating schema "public" to version "19 - creative workspace indexes" → Successfully applied 1 migration`。
只加 5 个索引，无列/约束变更。

## 后端测试

| 用例 | 结果 |
| --- | --- |
| `CreativeProjectServiceTest`（含 M0 新增用例） | ✅ 7/7（云库下耗时 146s） |
| `PromptOptimizationModesTest` | ✅ 5/5 |
| `BatchProgressAggregateTest` | ✅ 4/4 |
| `ShotStatusTest` | ✅ 1/1 |

> 环境备注：云数据库往返延迟显著（同一单测此前 5.5s，本轮 146s）。期间两次误判为"挂起"并 kill 掉
> surefire 进程，重新串行运行后全部通过；这不是代码问题。

## 浏览器 E2E（内嵌浏览器，零计费）

环境：1440×900 与 1280×800、真实视频项目「视频生成验收」（3 镜头 / 2 已选片 / 1 可生成）、真实候选视频。

| 检查点 | 结果 |
| --- | --- |
| 旧链接 `?tab=story` → `/story` 重定向 | ✅ |
| 项目壳：左导航（剧情/分镜/设定/工作流/素材/任务/快速生成）、项目头（名称/类型/状态/镜头 3/已选片 2/刷新） | ✅ |
| 分区高亮跟随路由，`?filter=pending` 不丢 | ✅ |
| 剧情页：剧集 + 场次、镜头数提示、「去分镜」、优化剧情/细化分镜入口 | ✅ |
| 分镜页：3 张镜头卡，2 张真实候选视频帧 + 1 张「未生成」占位；状态「已选定/已选定/可生成」；已采用徽标 2 个 | ✅ |
| 筛选「待选片」→ 空态「当前筛选下没有镜头。」；「全部」恢复 3 张 | ✅ |
| 批次面板就地展示（旧批次「E2E 停止路径验证」进行中 0/0），停止按钮可用 | ✅（只查看，未操作） |
| 镜头检查器：右侧 420px、列表仍可见（3 张卡不被遮挡）、候选显示 `960×540 · 5秒`、已采用三重表达、提示词/优化/生成计费提示 | ✅ |
| 深色主题：项目头标题、分区标题、卡片、状态徽标均可见（修复了继承 `--sk-label` 导致的黑底黑字） | ✅ |
| 1280 宽度：项目导航保持 200px、卡片三列不溢出 | ✅ |
| 视频项目「设定」分区：角色/地点/连续性内联渲染 | ✅ |
| 视频项目「素材」：17 个素材、来源筛选、对比/海报/继续创作入口 | ✅ |
| 视频项目「任务」：3 条任务表格 | ✅ |
| 视频项目「工作流」：空态与引导链接 | ✅ |
| 小说项目默认落「章节」，章节卡与设定面板正常 | ✅ |
| 图片项目默认落「创作」，参数面板与模型绑定显示正常 | ✅ |
| 全局页面 `/creative`、`/creative/works`、`/creative/assets`、`/creative/tasks`、`/workbench`、`/settings/model` 在新令牌下渲染正常 | ✅ |
| 旧链接 `?tab=chapters` → `/chapters` 重定向 | ✅ |

截图（会话工件，非仓库文件）：
- 分镜页（1440 浅色）：`/Users/zhuwenxuan/.zcode/cli/artifacts/sess_71b0158b-a57e-4953-8eee-2c4687c21504/call_00_AZ56qmNaDU7fRrDHxHV44493-tool-result-4454c71b-116b-4ec3-b861-d8c5df566f8f.png`
- 镜头检查器（1440 浅色）：`.../call_00_GxxrwFSOtYpEj6YB0SrU5784-tool-result-a349974f-01f6-4e26-b1cf-d681e30da5f7.png`
- 分镜页（1440 深色）：`.../call_00_ET_HsmkbCgmkYTgqIBGR3Q38086-tool-result-f90151db-3779-4b81-baed-6948be8db50f.png`
- 分镜页（1280 浅色）：`.../call_00_KTtj5PnoixqjLdCZhnGP9944-tool-result-33757136-168f-4a87-80a6-fd66bdec2437.png`

## 缺陷修复

`docs/bugs/bug-2026-11-02-storyboard-candidate-shape.txt`：workspace 的候选是扁平记录，分镜页按 `CandidateView`
嵌套结构读取，导致镜头卡整块不渲染。已统一口径并复验。

## 未测 / 已知限制

- 未做真实「生成视频」调用（计费约束）；生成按钮、计费提示、失败候选重试入口只验证了渲染。
- 未做 390×844 与 200% 缩放逐项检查；<1100px 的项目导航收起与检查器覆盖只按样式实现，未逐像素验收。
- 批量生成只验证了面板与已有批次，未创建新批次（避免真实提交）。
- 概览分区（规范 §8.3）未实现，进度与待选片入口已提到项目头。
- 「设定」分区仅 VIDEO 项目提供；小说项目的作品设定仍在章节页内联。

## 回滚

前端：恢复 `router/index.js` 的项目路由与 5 个旧组件（`CreativeProject.vue`、`StoryWorkspace.vue`、`ShotDrawer.vue` 已删除，需 `git revert`）。
后端：V19 仅索引，可保留；候选 join 与计数优化可单独回滚。

## 追加：素材库与任务记录的 UI 修正（同日，用户反馈后）

用户反馈「整体 UI 变化不明显」并指出素材库网格错乱（卡片高低不齐、大片留白、按钮换行）。定位与修复见
`docs/bugs/bug-2026-11-02-asset-grid-ragged.txt`。

| 检查点 | 结果 |
| --- | --- |
| 素材库（1440×900，浅色）：17 张卡高度 277–280px 一致、无空白块、操作一行、左侧筛选栏（来源/类型/项目/收藏） | ✅ 截图见下 |
| 素材库（深色）：文字/边框/筛选栏可读，卡片一致 | ✅ |
| 项目素材：3 张卡 289px 等高，复用同一 `AssetCard` | ✅ |
| 任务记录：状态分段筛选（全部 10 / 需处理 2 / 已完成 8 / 进行中 0 / 已取消 0），行高 44px | ✅ |
| 构建：`npm run build` | ✅ `✓ built in 18.06s` |

截图（会话工件）：
- 素材库（1440 浅色）：`.../call_00_ET_NErv7p8XXVrpx4zbgchx3617-tool-result-07816f95-98fe-43a0-bf0b-1b78aee40638.png`
- 素材库（1440 深色）：`.../call_00_QlmT8u2GyRclpbr9QGyM6694-tool-result-bf3ec085-c3ac-4681-99a6-a03309517596.png`
- 项目素材（1440 浅色）：`.../call_00_DUqwITJBTZCtEfQ11NJo1373-tool-result-9f4c8942-7ebd-49ae-bfae-9122ef8786d3.png`
- 任务记录（1440 浅色）：`.../call_00_KemD7qx2JkSLor7Xn6vE1981-tool-result-c2eef675-1ec0-4163-b813-e503caccd8ba.png`

未做：素材库的「业务分类（角色/场景/镜头）」「使用位置反查」「回收站」仍缺后端字段与端点（M2 范围）；
任务中心仍是全局列表（项目内已有分区），跨项目「待处理」聚合未做。
