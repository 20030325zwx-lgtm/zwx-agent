# 创作体验重设计 D1 + 技能模块 P0 实施会话（2026-11-03）

## 改动内容

按两份设计文档（2026-11-02-creative-experience-redesign / 2026-11-02-video-skill-module）完成第一阶段实施：

- **D1 设计底座**：`design/tokens.js` 新增 `--cr-*` 语义 token 层（浅/暗双主题、鸢尾紫强调、石墨主按钮、动效时间 token instant~showcase 与缓动曲线）；`apply-tokens.js` 增加应用内「减少动态效果」三态开关（`data-cr-motion`）；`base.css` 全局 reduced-motion 规则。全部 `--wb-*` 旧 token 保留。
- **信息架构**：`GlobalNav.vue` 一级导航收敛为 创作/我的创作/素材库/技能 4 项；顶部新增「生成活动」入口（`activity.js` 组合式函数 + `ActivityDrawer.vue`，30s 轮询 summary）；底部新增动效开关。路由：新增 `/creative/library`、`/creative/skills`；`/creative/works` → redirect library；`/creative/tasks` 保留为「生成记录」深层入口。
- **技能模块后端**（P0 最小集）：`V21__creative_skill.sql`（creative_skill / creative_skill_version / creative_skill_run 三表）；新包 `creative/skill/`（Record×3 + Repository + Service + Controller）；内置 4 个自行编写的 P0 技能种子（视频提示词导演/一图短片/商品展示短片/剧本转分镜）；兼容状态按用户绑定实时计算（READY/MISSING+missing 列表）。API：`GET /ai/creative/skills`、`GET .../skills/{slug}`、`POST .../projects/{id}/skill-runs`、`GET/POST .../skill-runs/{id}`(+retry-plan/confirm)。
- **技能运行语义**：方案生成只走 chat 模型（不伪造视觉分析）；确认时服务端派生幂等键 `skill-run:{runId}` 经 `MediaJobService.create` 提交（JobSourceType.UI + sourceRunId=runId），MediaJob 仍是状态真相；方案失败留 run 记录可原位重试。
- **前端技能库**：`skills/CreativeSkills.vue`（搜索/分类/阶段/可用筛选 + 卡片）+ `SkillDetailDrawer.vue`（使用说明/输入输出/版本来源）。
- **创作首页**：输入台（类型+提示词+可选技能）+「进入创作台」语义（提交才叫生成）+ 四种起点导航 + 输入本地草稿（localStorage）+ sessionStorage 技能交接（不把提示词放 URL）。
- **创作台**：`ProjectStudioTab.vue` 新增技能区块（选技能→生成方案→可编辑→确认生成/采用到输入台；方案失败重试）。
- **我的创作**：`ProjectLibrary.vue` 项目/作品双视图；作品=成功任务产出聚合投影；后端 `MediaJobOutputRepository.findByJobIn` 批量附带 outputCount/firstAssetId（单次 IN 查询）。
- 文案修正：`CreativeTasks.vue` 移除「当前开放图片生成」过时表述。
- `MediaJobService.operationAllowedForKind` 改 public（领域规则复用）；`CreativeJson` 增加 readStringList/readMap。

## 验证结果

- `mvn -DskipTests package` 通过；前端 `npm run build` 通过（chunk 体积警告为既有 file-viewer 依赖，非本轮引入）。
- launchctl 重启后 health OK；Flyway 在后端实际连接的库（127.0.0.1:15432 SSH 隧道远端库）应用到 V21。
- API 实测：技能列表 4 项 compat=READY；详情含正文；创建 run→PENDING_CONFIRM 结构化方案（summary/prompt/warnings/missing）真实生成成功；详情/归档均正常。
- 浏览器走查（1280px）：技能库卡片与详情抽屉、首页输入台+四种起点、我的创作作品视图（真实封面+版本数）、创作台技能区块 UI 方案生成成功、旧路由重定向、暗色主题生效。
- 测试残留已清理：验证用项目已归档（名"技能链路验证-可删除"）；mv 项目被污染的草稿已清空；主题偏好已恢复浅色。**未发起任何计费生成**（方案确认到创建 MediaJob 为止未实测）。

## 遗留事项

- 确认执行（confirm→创建 MediaJob）只验证了接口实现，未做真实端到端（涉及计费）；首帧图生视频的网关真实转发验证仍欠（前序遗留）。
- 素材库改版（原比例/网格切换、多选工具条、大预览、上传面板）未做——D2 范围；「从素材开始」起点目前跳素材库，素材卡「用作参考」语境动作未建。
- 作品视图为任务聚合投影，版本组/精选/封面选择等领域字段（redesign §8 数据增量提案）未建；「我的技能」页签因缺 installation 模型未做（不做假入口）。
- Motion for Vue 未安装（按文档需先核实版本/许可/Electron 兼容）；当前动效全部 CSS 过渡。
- 技能 run 的 run_step/多任务关联、外部导入（P0-C）、多镜头状态机（P1）未做。
- 旧远端 worker 部署问题（前序遗留）未动。

## 环境注意事项

- 后端数据库是 **127.0.0.1:15432 的 SSH 隧道远端库**，不是 docker 容器 `yu-ai-agent-postgres`（5432）；docker 容器当时处于停止状态也不影响本地服务，验证 SQL 时不要查错库。
- 登录凭据用前端默认 admin/admin123（Login.vue 内置默认值）可拿 token 做 API 验证。
- 未提交任何 git 变更（含前日 4 份设计文档仍为未跟踪）。
- 不触碰 `application-local.yml`。
