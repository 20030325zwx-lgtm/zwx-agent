# M1 短剧项目工作区会话（2026-11-02）

## 改动内容

按 `docs/design/2026-11-02-ai-creative-core-product-plan.md` M1 与 `2026-11-02-ai-creative-ui-visual-design.md` UI0/UI2 实施：

- **UI0 令牌**：`design/tokens.js` 按规范换色/圆角/阴影，新增排版与 4px 间距令牌、媒体舞台色；`style.css` 去掉 page 背景径向渐变、中性化 `--sk-*`；收藏反馈由粒子改为图标缩放。
- **项目壳**：`CreativeProject.vue`（2135 行）删除，改为 `views/creative/project/ProjectShell.vue` + 8 个分区子路由组件；左侧项目导航 200px（<1100px 收 56px）；旧 `?tab=` 深链自动重定向并保留 runId/jobId。
- **共享状态**：`project/composables.js`（`useProjectJobs`、`useStoryWorkspace`、`useWorkflowList`、`studioSeed`），任务与剧情各只轮询一份。
- **剧情/分镜拆分**：剧情只留剧集与场次；分镜页改为媒体优先镜头卡（`ShotCard.vue`）+ 进度/阻塞筛选 + 就地批次面板。
- **镜头检查器**：`ShotDrawer.vue` → `story/ShotInspector.vue`，≥1100px 右侧 420px 不遮挡列表，候选 2 列网格、已采用三重表达、失败候选原因与重试。
- **后端**：候选查询 join 素材摘要（分辨率/时长）；workspace 去掉 `candidateCounts` 的 N+1；新增 V19 索引迁移。
- 缺陷：workspace 候选扁平结构与 CandidateView 混用导致分镜页不渲染，已修复并记录 `docs/bugs/bug-2026-11-02-storyboard-candidate-shape.txt`。

## 验证结果

- 单测：`CreativeProjectServiceTest` 7/7、`PromptOptimizationModesTest` 5/5、`BatchProgressAggregateTest` 4/4、`ShotStatusTest` 1/1；`npm run build`、`mvn -DskipTests package` 通过。
- 迁移 V19 启动时成功应用。
- 浏览器零计费验收：旧链接重定向、项目壳与分区、分镜 3 卡（2 真实视频帧）、待选片空态、右侧检查器（960×540·5秒）、深浅色、1280 宽度、视频/小说/图片三类项目、全局页面全部通过。
- 证据：`docs/test/results/2026-11-02-m1-project-workspace.md`。

## 遗留事项

- 概览分区未做（进度与待选片入口已在项目头）。
- 任务中心 UI3（状态分组/详情）与素材中心 M2 未做；素材「用于镜头」反查仍只有镜头候选，缺角色/地点。
- 未做真实生成、批量新建、390 宽与 200% 缩放逐项验收。
- 小说项目的「作品设定」仍在章节页内联，未并入独立分区。

## 环境注意事项

- 云数据库当前往返延迟高：`CreativeProjectServiceTest` 单类耗时 146s（此前 5.5s）。跑测试不要用短超时 kill，
  会误判"挂起"；串行运行更稳。
- 前端 dev server 需在批量改名/删除组件后重启一次（`launchctl kickstart -k ...frontend`），否则 HMR 会残留旧模块导致"渲染空白"的假象。
- 后端已重启加载新 JAR（V19 与候选 join 生效）；`application-local.yml` 未读取、未改写；验收全程未触发生成计费。
- 验收后主题已切回浅色。
