# 会话摘要：制作蓝图落画布（2026-11-07）

## 需求与方案

用户希望视频制作最后一步能把五阶段编排流程体现在画布中（「给人一种很厉害的感觉」）。
实现为只读展示节点 `pipeline_stage`；随后按用户反馈把分镜收敛为**一个大节点列表形态**
（`STORYBOARD`，内部滚动、镜头行内状态/时长/摘要/已选片段悬停播放）。
链条恒定 4 阶段：`开始→画风→故事→分镜→成片`。
详见 `docs/design/2026-11-07-production-blueprint-canvas.md`。

## 改动

- 后端 `CreativeCanvasService`：白名单 + 蓝图节点校验 + 只允许执行连线（不进运行域）。
- 前端：`canvasGraph.js`（连线守卫）、`CanvasGraphNode.vue`（蓝图渲染分支）、
  `ProjectCanvasTab.vue`（buildProductionBlueprint/applyBlueprint/工具栏按钮/sessionStorage 交接）、
  `VideoAssemblyStage.vue`（成片阶段「在画布生成制作蓝图」按钮）。

## 验证

- `CreativeCanvasServiceTest` 21/21（蓝图链/分镜列表/喂生成节点拒绝/坏 ID 拒绝）；
  creative 回归 49/49；前端 build 通过。
- 本地浏览器实测（admin，项目「生成小猫睡觉」4 镜头）：成片阶段按钮 → 自动跳画布生成；
  大节点版再同步成功且旧单镜节点被替换，「已保存」确认后端校验通过。
  未调用任何生成模型（项目无候选，视频预览待用户真实生成后验收）。

## 省察修正（第二轮）

- 蓝图节点 footer 全部带「同步于 HH:mm」（快照诚实性）；连线标签改「编排顺序」并让
  `normalizeConnection` 保留已存标签；成片节点标题带「缺 N 镜」（按镜头无已选片段计算）；
  画风节点补画幅/目标时长；分镜行视频 `preload="none"`。均为前端改动，后端无变化。

## 遗留

- 用户今晚真实生成验收（Seedance 首条 ¥7–9 + 成片导出）后，蓝图里的镜头/成片节点
  会有真实视频可播放；重新点一次「制作蓝图」即可刷新快照。
- 已知既有失败与本轮无关：ToolGuardHookTest / MediaJobSchedulerTest /
  PgVectorVectorStoreConfigTest（DashScope 欠费）。

## 注意

- 蓝图节点 id 固定（bp-*），再同步按 id 替换并保留拖动位置；用户手动删除后同步即恢复。
- `sessionStorage('aster.creative.blueprint')` 是跨 tab 交接标记，画布 loadCanvas 消费后即清除。
- **服务器镜像清理教训（2026-11-07）**：手动 `docker rmi` 旧版本镜像时，必须保留
  `/opt/aster/.env` 的 `IMAGE_TAG` 所引用的 tag（compose 全部服务都用 `${IMAGE_TAG}`）。
  未变更服务不重建容器，运行镜像落后于 IMAGE_TAG（如 backend 跑 0.7.19、IMAGE_TAG=0.7.20），
  旧 tag 被删会让下次容器重建失败。恢复方式：`docker tag <运行中镜像> aster-xxx:${IMAGE_TAG}`
  （部署脚本对未变更服务本就如此重标签，内容等价）。
  另：browser-mcp / artifact-mcp 的 0.7.20 tag 缺失是历史遗留（两服务从未在该服务器启动，无影响）。
- 服务器 nginx 无 proxy_cache；`index.html` 已是 `Cache-Control: no-cache`，无需清 HTTP 缓存。
