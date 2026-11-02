# 创作体验 D2/D3 + 动效 + Skill 导入与逐镜 实施会话（2026-11-03 续）

前一份 [session-2026-11-03-creative-redesign-d1-skill-p0.md](session-2026-11-03-creative-redesign-d1-skill-p0.md) 交付 D1+Skill P0；本份交付用户指定的后续四批次。两份均有效，按时间顺序阅读。

## 改动内容

### 批次 1｜D2 素材闭环（redesign §5.3/§5.4）
- 后端：素材列表新增 `q` 名称模糊搜索（`CreativeAssetRepository/Service/Controller`，通配符转义）。
- `CreativeAssets.vue` 重构：单行工具栏（标签页/搜索/类型 chips/布局切换/密度/筛选按钮）；可收起筛选抽屉（来源/业务分类/项目/收藏）；**原比例视图**（grid 行 span 按 aspect-ratio 计算，DOM 顺序=键盘顺序，未用 CSS columns）；搜索防抖 + 过期响应丢弃（requestSeq）。
- 新组件 `MediaTile.vue`：预览+短标题+类型/时长，hover 显收藏，勾选框独立多选，视频 hover 播放/移出停止。
- 多选底部工具条：引用到创作台（单张，经 handoff）、下载、移至回收站（HoldToConfirm）/恢复；跨页选择范围提示。
- 上传面板：拖入投放区+逐文件进度（axios onUploadProgress）+失败独立重试；目标项目必选；关闭面板不取消已开始上传。
- 新组件 `MediaLightbox.vue`：深色舞台+300px 信息栏；←/→ 切换、Esc 关闭（父级恢复滚动位）；图像点击缩放；视频点击播放不自动发声；主动作按类型（沿用提示词/用作首帧；视频的「作为图片参考」明确禁用）；同批版本条（sourceJobId→job outputs）+ 复用 `AssetCompare` 对比选定；业务分类编辑（rail-extra slot）。
- handoff 直通：素材库「用作首帧」→ 首页输入台（chip 可见可清除）→ 项目 studio 预设 image_to_video+首帧。

### 批次 2｜D3 沉浸式创作台（redesign §5.5）
- `ProjectStudioTab` 改为「中央结果舞台 + 底部输入台 + 参数侧栏」三区 grid；生成按钮/模型/草稿状态移入底部输入台（窄屏置顶 sticky）；空态文案同步。
- 轮次候选样式升级：深色媒体舞台、更大候选图。

### 批次 3｜动效库
- **motion-v 2.10.0 已安装**：核实 MIT、635KB unpacked（ESM 摇树）、peer vue≥3.0（项目 3.5.14）+ @vueuse/core；WAAPI/rAF 在 Electron Chromium 兼容。
- 接入 2 处：大预览 backdrop/媒体交叉淡入；创作台结果就绪 fade+y(8px) 180ms。均经 `useReducedMotion` 支持减弱动态效果。其余动效仍为 CSS（token 时间曲线）。

### 批次 4｜Skill P0-C 导入 + P1 逐镜（MVP）
- `V22__creative_skill_import_run_jobs.sql`：`creative_skill_import`（预检记录，同 hash 幂等）+ `creative_skill_run_job`（run↔job 一对多，含 shot_index/title）。
- 后端：导入预检（front-matter 解析、正文 ≤60KB、兼容报告 issues/compat）→ 确认保存 **PRIVATE** 技能（license_status=PENDING）；目录可见范围=系统+本人私有（`listVisibleFor`，item 带 `mine`）；slug 规范化与查重。
- 逐镜生成：`POST /skill-runs/{id}/confirm-shots`——plan.shots 每镜独立 MediaJob（幂等键 `skill-run:{id}:shot:{i}`），单批上限 20 镜，失败镜头走现有 job retry；单镜头 confirm 对多镜头方案明确拒绝并引导逐镜。
- `script-to-storyboard` 增加 TEXT_TO_VIDEO 能力（用于逐镜）；前端 plan 区块多镜头时显示「确认并逐镜生成（N 镜）」+ 确认后逐镜状态列表与刷新。
- 技能库「导入技能」对话框（粘贴 Markdown→预览→slug→保存私有）。

## 验证结果

- 前端 build ✓（motion-v 集成后 31.5s 无错）；后端 `mvn -DskipTests package` ✓；V22 已应用（远端库 15432 隧道）。
- API 实测：`q` 搜索命中正常；导入闭环（预检 compat=READY → 确认 mine=true → 列表 5 项可见）。
- 浏览器走查：素材库原比例/网格、多选工具条（截图确认）、大预览（舞台+信息栏+禁用逻辑）、创作台三区布局、技能导入对话框。
- **未发起计费生成**：逐镜 confirm-shots 端到端（会创建 text_to_video 任务）未实测，仅实现与编译/接口验证。

## 遗留事项

- 逐镜生成的真实端到端（含失败镜头单独重试演练）待用户授权计费后验证。
- 上传大小限制文案（图片≤20MB/视频≤500MB）为前端约定，后端实际上限未核对——需对照 `CreativeAssetController` 上传校验后统一。
- 视频版本比较仅「并排分屏」级别（AssetCompare 为图片并排选定），视频同步播放/暂停未做（§5.4 后续）。
- hover 素材卡的「更多」菜单（重命名/选封面/归档）未做；「归入项目」批量动作缺后端支持，未做假按钮。
- 前日测试残留：归档项目「技能链路验证-可删除」与私有技能 `test-import-skill`（本轮导入链路验证产物，仅 admin 可见；可在库中自行确认或 DB 清理）。
- motion-v 未测路由分包增量与长列表性能（redesign §7.4 验收项）。

## 环境注意事项

- 后端数据库仍是 `127.0.0.1:15432` SSH 隧道远端库；docker 容器 yu-ai-agent-postgres 与本地服务无关。
- 登录验证用 admin/admin123（前端默认值）。
- 本轮含 `npm install motion-v`（package.json/package-lock.json 变更）。
