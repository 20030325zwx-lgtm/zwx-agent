# M0 产品外壳收敛：验收证据

日期：2026-11-02。基线：`6c0ff57` + 本批改动。**真实生成计费：0 次**（本批只做外壳与列表查询，未触发生成）。

## 构建

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -DskipTests package` | ✅ 产出 `target/aster-agent-0.0.1-SNAPSHOT.jar` |
| 前端 | `npm run build` | ✅ `✓ built in 16.96s`（仅既有 chunk 体积警告） |

## 后端测试

| 用例 | 结果 |
| --- | --- |
| `CreativeProjectServiceTest`（既有 6 项 + 新增 1 项） | ✅ 7/7，`Tests run: 7, Failures: 0, Errors: 0` |

新增 `listsProjectsWithKindFilterSearchAndCover` 覆盖：
- `kind=VIDEO` / `kind=IMAGE` 过滤命中；
- `q=雨夜` 命中、`q=%` 被转义不当作通配；
- 无素材时 `coverAssetId`/`coverMediaType` 为空；
- 更新较早创建的项目后，默认排序（最近更新）把它提到最前；`sort=created` 仍然后建在前 → 证明排序真的切换；
- `sort=popular` 抛参数错误。

未跑全量回归：本批未改 MediaJob/调度/批次代码；云库环境下既有 `MediaJobSchedulerTest` 时序抖动与本文无关。

## 浏览器 E2E（内嵌浏览器，零计费）

环境：后端 `127.0.0.1:8123`（重启加载新 JAR）、前端 `127.0.0.1:3000`、真实登录态、真实已有作品。

| 检查点 | 结果 |
| --- | --- |
| `/` 默认落到创作首页（旧 `/home` 同样） | ✅ 地址变为 `/creative` |
| 导航分组：创作/作品/素材/任务 + 更多（工作流/知识库/智能体/服务概览） | ✅ 快照与截图一致 |
| 高亮唯一性：`/creative/works` 只亮「作品」，`/creative/assets` 只亮「素材」，`/creative/tasks` 只亮「任务」，项目页亮「创作」 | ✅ 逐页读取 `.wb-nav-item.active` 验证 |
| 一句话创作：输入想法 → 开始创作 → 落到新项目，想法已预填到「你的想法 / 最终提示词」，显示「草稿已保存」 | ✅ 新建作品 `M0 验收：清晨海面…`（测试产物，保留在列表） |
| 空想法时「开始创作」禁用 | ✅ `[disabled]` |
| 首页最近作品：4 个项目、真实封面（视频项目取视频帧，图片项目取缩略图，小说与无产物项目显示占位） | ✅ 截图中视频项目与图片项目封面均为真实产物 |
| 首页待处理：`0 生成中 / 2 最近失败 / 0 结果待确认`，失败条目可点开对应项目的任务 | ✅ 计数与列表同源 |
| 作品页类型筛选（视频 → 仅 1 个视频项目） | ✅ |
| 作品页搜索「星槎」→ 1 条命中；「zzz不存在」→ 空状态文案 | ✅ |
| 作品页排序切换、游标加载更多 | ✅ 共 4 个作品，单页无游标时显示总数 |
| 回归：视频项目「剧情」tab（剧集/场次/分镜/批量生成面板） | ✅ 渲染正常 |
| 回归：小说项目默认落「章节」tab（设定 + 章节） | ✅ |
| 回归：图片项目「素材库」tab（14 个素材缩略图） | ✅ |
| 回归：`/workbench`、`/workflows`、`/knowledge-admin` 从「更多」可达 | ✅ 各自高亮正确 |
| 深色主题、浅色主题首页渲染 | ✅ 两版截图；验收后已切回浅色 |

截图（会话工件，非仓库文件）：
- 作品列表：`/Users/zhuwenxuan/.zcode/cli/artifacts/sess_71b0158b-a57e-4953-8eee-2c4687c21504/call_00_fK0LU5PHaoqNwHKucrQl0579-tool-result-de041499-20a9-4934-8a45-442a86ed1ea7.png`
- 首页（浅色）：`.../call_00_XsyTqlUzPjF9xiWwiPZV4508-tool-result-6071381c-fe41-4765-b68d-28fc0726e828.png`
- 首页（深色）：`.../call_00_dy4uJ36vio7YwS38ayVX0114-tool-result-dc698e4d-a1cf-4f5e-89cf-922271b604b7.png`

## 未测 / 已知限制

- 未在移动端窄屏（<720px）做逐像素验收，只做了响应式样式编写；导航窄屏仍收成图标栏（既有行为）。
- 「继续上次编辑位置」（回到具体镜头）未做，首页只到作品级。
- 测试新建的 `M0 验收：清晨海面…` 项目保留在作品列表，未删除（项目暂无删除端点，只有归档）。
- 真实批次提交、上游取消能力仍待授权，与本批无关。

## 回滚

纯前端 + 三个文件的后端查询改动，无迁移。回滚只需还原 `GlobalNav.vue`、`router/index.js`、`CreativeHome.vue`、`ProjectLibrary.vue`、`ProjectCard.vue`、`api/index.js` 与 `CreativeProjectRepository/Service/Controller`；数据库无变更。
