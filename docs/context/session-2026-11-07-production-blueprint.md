# 会话摘要：制作蓝图落画布（2026-11-07）

## 需求与方案

用户希望视频制作最后一步能把五阶段编排流程体现在画布中（「给人一种很厉害的感觉」）。
实现为只读展示节点 `pipeline_stage`（STYLE/STORY/SHOT/FINAL_CUT）组成的活动节点链：
`开始 →画风→故事→镜01…→成片`，镜头节点内嵌播放已选片段、成片节点播放导出 MP4。
详见 `docs/design/2026-11-07-production-blueprint-canvas.md`。

## 改动

- 后端 `CreativeCanvasService`：白名单 + 蓝图节点校验 + 只允许执行连线（不进运行域）。
- 前端：`canvasGraph.js`（连线守卫）、`CanvasGraphNode.vue`（蓝图渲染分支）、
  `ProjectCanvasTab.vue`（buildProductionBlueprint/applyBlueprint/工具栏按钮/sessionStorage 交接）、
  `VideoAssemblyStage.vue`（成片阶段「在画布生成制作蓝图」按钮）。

## 验证

- `CreativeCanvasServiceTest` 20/20（含 2 个新蓝图用例）；creative 回归 49/49；前端 build 通过。
- 本地浏览器实测（admin，项目「生成小猫睡觉」4 镜头）：成片阶段按钮 → 自动跳画布生成
  完整链条，状态「已保存」；工具栏重复同步无重复节点。未调用任何生成模型（项目无候选，
  视频预览待用户真实生成后验收）。

## 遗留

- 用户今晚真实生成验收（Seedance 首条 ¥7–9 + 成片导出）后，蓝图里的镜头/成片节点
  会有真实视频可播放；重新点一次「制作蓝图」即可刷新快照。
- 已知既有失败与本轮无关：ToolGuardHookTest / MediaJobSchedulerTest /
  PgVectorVectorStoreConfigTest（DashScope 欠费）。

## 注意

- 蓝图节点 id 固定（bp-*），再同步按 id 替换并保留拖动位置；用户手动删除后同步即恢复。
- `sessionStorage('aster.creative.blueprint')` 是跨 tab 交接标记，画布 loadCanvas 消费后即清除。
