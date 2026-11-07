# 制作蓝图：五阶段编排流程落画布（2026-11-07）

需求：视频制作「最后一步」能把设计好的编排流程（画风→故事→分镜→视频→成片）直接体现在画布中。

## 方案

新增**只读展示节点** `pipeline_stage`（四种形态 STYLE/STORY/SHOT/FINAL_CUT），
前端一键把制作数据组装成画布节点链并保存：

```
[开始] →[阶段1 画风]→[阶段2 故事]→[镜01]→[镜02]→…→[阶段5 成片]
```

- 链条挂已有开始节点下（空画布由 ensureStart 补齐），用蓝色「执行顺序」连线；
  一行 5 个自动换行，摆在已有内容下方；空项目也生成（画风/故事/成片三节点占位）。
- 镜头节点：状态徽标（草稿/待生成/生成中/待选片/已选片）+ 目标时长 + 运镜 + 提示词摘要；
  已选片段的**直接内嵌播放**（悬停自动播放）；成片节点播放导出的 MP4（带控件）。
- 可重复「同步」：按稳定 id（bp-style / bp-story / bp-shot-<shotId> / bp-final）整体替换，
  保留用户拖过的位置；撤销栈支持回退。
- 数据来源全部为现有接口：项目设定（画风）、story/workspace（剧集剧本+镜头+候选）、
  成片草稿与导出历史（listFinalCuts 最近 COMPLETED 的产物）。不新增后端读接口。

## 入口

1. 制作视图·成片阶段「在画布生成制作蓝图」→ sessionStorage `aster.creative.blueprint` 交接 → 跳画布自动生成。
2. 画布工具栏「制作蓝图」按钮（仅 VIDEO 项目显示），随时重新同步。

## 后端改动（加法，最小化）

- `CreativeCanvasService`：NODE_TYPES 加 `pipeline_stage`；校验
  kind ∈ 四形态、assetId 必须合法 UUID、text ≤2000 字；
  **蓝图节点只允许执行顺序连线**（sourceHandle 必须 flow-out）——不可给生成节点当输入，
  不进入可执行计划（includedNodes 只收生成节点，运行域完全不受影响）。
- 测试：`CreativeCanvasServiceTest` 蓝图链通过且不可运行、喂给生成节点/坏 kind/坏 assetId 被拒。

## 前端改动

- `canvasGraph.js`：`isPipelineStage`；手动连线涉及蓝图节点一律拒绝（提示只读展示）。
- `CanvasGraphNode.vue`：pipeline 分支渲染（渐变色卡/剧本摘要/镜头视频/成片播放器），
  只有 flow 端口；`plPreviewUrl` 走既有 `canvasPresentation.assetUrl` 预览缓存。
- `ProjectCanvasTab.vue`：`buildProductionBlueprint/applyBlueprint`；预加载循环补
  `node.data.assetId`；loadCanvas 消费 sessionStorage 交接标记。

## 验证

- 后端 49 用例通过（含 2 个新蓝图用例）；前端 build 通过。
- 本地实测：项目「生成小猫睡觉」（4 镜头、无候选）成片阶段按钮 → 跳画布自动生成
  开始→画风(都市情感渐变)→故事(23 字剧本)→镜01–04→成片(尚未导出) 链条，
  「已保存」确认后端校验通过；再次同步无重复节点。
- 视频内嵌预览路径与既有节点预览同机制（签名 URL）；真实候选片段预览待用户今晚首条生成后验收。

## 迭代：分镜收敛为大节点（同日，用户反馈）

用户希望分镜式内容收进**一个分镜专用大节点**（列表形态）而不是每镜一个节点。已改：

- 新形态 `STORYBOARD`：`data.shots[] = {shotId, shotNumber, status, durationSeconds, promptText, camera, assetId}`，
  上限 40 行（`MAX_STORYBOARD_ROWS`），逐行校验 assetId UUID。
- 节点宽 330px，列表内部滚动（`nowheel nopan` + `overscroll-behavior: contain`），
  行内：镜号 + 状态徽标 + 时长 + 提示词摘要（2 行截断）+ 已选片段悬停播放。
- 链条变为恒定 4 阶段：`开始→画风→故事→分镜→成片`；布局按节点实际宽度横排
  （STORYBOARD 330，其余 236）；全部阶段都有旧位置时沿用拖过的布局。
- 旧的 `SHOT` 单镜形态保留在白名单（已保存画布仍合法），但蓝图不再生成；
  重新同步按类型整体替换，旧 bp-shot-* 自动清掉。
- 测试：21/21（新增分镜列表通过/超 40 行拒绝/坏素材 ID 拒绝）。

## 边界

- 蓝图是快照不是映射：重新生成/换选后需点一次「制作蓝图」刷新；节点删除后同步即恢复。
- 不改变运行域：蓝图节点永不执行、不计费；生成节点链路照旧。
