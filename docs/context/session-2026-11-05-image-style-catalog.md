# 图片提示词优化：摄影风格目录 + 跟随原片（2026-11-05）

## 背景（用户问题）

原来图片提示词优化只有「写实 / 漫画」两种风格，且优化时看不到用户传入的照片：
- 传入自己的照片（素材/上游结果）做编辑时，希望改写后的提示词能沿用这张片的风格；
- 希望内置一批摄影取向的提示词能力，而不是只有一种「像照片」。

## 本次改动

### 1. 风格目录（新增 `creative/support/ImageStyleCatalog.java`）

13 种风格，前端下拉与后端规范共用一份口径，新增风格只改这一个文件：

| 分组 | 编码 | 标签 | 要点 |
| --- | --- | --- | --- |
| 摄影 | PHOTO_MATCH | 跟随原片 | 读参考片的镜头/光线/调色，只改写内容（需参考图） |
| 摄影 | REALISTIC | 自然写实 | 原「写实」规范，保持向后兼容 |
| 摄影 | FILM_PORTRAIT | 胶片人像 | Portra 400、85mm f/1.8、低饱和暖调、细颗粒 |
| 摄影 | BLACK_WHITE | 黑白摄影 | 银盐层次、侧硬光、颗粒与暗角 |
| 摄影 | DOCUMENTARY | 纪实街拍 | 28–35mm 抓拍、现场混合光、不摆拍 |
| 摄影 | CINEMATIC | 电影感 | 宽银幕、低调光与实用光源、青橙/冷调分级 |
| 摄影 | LANDSCAPE | 风光 | 广角层次、黄金/蓝调时刻、长曝光 |
| 摄影 | PRODUCT | 产品棚拍 | 柔光箱＋轮廓光、100mm、材质与色准 |
| 摄影 | RETRO_CCD | 复古 CCD | 直闪、偏色、噪点、低保真随手拍 |
| 摄影 | JAPANESE_FRESH | 日系清新 | 高亮低对比、青绿偏色、逆光空气感 |
| 摄影 | MOODY_NIGHT | 夜色氛围 | 蓝调时刻、霓虹混合光、湿地面反光 |
| 摄影 | MACRO | 微距质感 | 100mm 1:1、极浅景深、纹理与露珠 |
| 插画 | COMIC | 漫画插画 | 原子风格＋墨线＋赛璐璐规范 |

实现方式：摄影风格共用一套骨架提示词（主体/环境/镜头/光线），只注入各自的「风格锚点」rubric，
避免每种风格重复一份完整系统提示词。

### 2. 跟随原片（PHOTO_MATCH）

- 前端把当前节点的参考图 ID（连接素材＋手动添加，最多 2 张）随请求提交；
- 服务端按 ID 校验归属后读取：优先缩略图（体积小、够判风格），无缩略图回退原图，8MB 上限；
- 以多模态方式把图片与文本一起送入视觉模型，系统提示词要求只提取风格要素、不复述参考片内容；
- 参考者为空时返回 400「「跟随原片」需要先连接或添加一张参考图片」；
- 视觉模型选择：vision 用途优先，未绑定时回退 optimize/chat，都不可用才提示到「设置→模型绑定」。

### 3. 前端画布（`ProjectCanvasTab.vue`）

- 下拉改为按目录分组的 `<optgroup label="摄影风格|插画风格">`；未连接参考图时「跟随原片」置灰并标注「（需参考图）」；
- 选择按节点记忆（`nodeStyles` Map），不再是全局一个风格；
- 节点有参考图时默认「跟随原片」，没有参考图默认「自然写实」；
- 下拉下方显示该风格的短提示语；目录请求与素材/模型并行加载，接口失败时退化为最小可用集。

### 4. 顺带修复的缺陷（详见 `docs/bugs/bug-2026-11-05-dashscope-image-url-error.txt`）

DashScope 原生协议默认走 text-generation 端点，带图片会报 `url error`。
新增 `DynamicChatModelFactory.multimodalModel(config)`（`withMultiModel(true)`，缓存键区分），
提示词优化与 `WorkflowService.understandLocalAsset` 均改用它。

## 验证

- 后端单测 `PromptOptimizationModesTest`：10 项通过（目录唯一性与顺序、每种风格都注入自己的锚点、
  跟随原片不预置具体胶片取向、编码归一化与非法值拒绝）。
- 接口实测（本地 admin）：
  - `GET /api/ai/creative/prompts/image-styles` 返回 13 条分组目录；
  - `CINEMATIC` 优化输出含「85mm f/1.4／低机位／橱窗暖光＋冷白路灯／电影感 2.39:1」；
  - `PHOTO_MATCH` 无参考图 → 400 友好提示；
  - `PHOTO_MATCH` 带一张真实素材 → 200，输出为「50mm 自然光人像、柔和侧逆光、暖调、低对比、奶油白＋浅灰蓝、
    轻微胶片颗粒」＋新描述的内容，未复述参考片主体。
- 前端 `npm run build` 通过；浏览器冒烟 `frontend/tests/canvas-browser-smoke.cjs`（Playwright + Chrome，全 API mock）
  通过，新增断言：分组数 2、无参考图默认自然写实、跟随原片禁用、切换电影感后提示语与 `imageStyle` 正确提交。

## 发布记录（0.7.11 → 0.7.12）

- 0.7.11：bf7af13（图标缓存）→ 6f4f1f8（后端风格目录 + 多模态修复）→ e6ff32b（画布风格下拉 + 冒烟）→
  c6ca4a4（另一会话的左侧导航悬浮圆角样式）；线上核对通过。
- 0.7.12：同一份代码的干净重建（修掉 0.7.11 重试导致同名镜像内容漂移），并清掉 `public/vendor`
  里累积的重复副本（dist 由含重复副本的规模降到 72MB，容器内 70MB）。
- 镜像：aster-backend/frontend:0.7.12，md5 双端校验一致；无迁移，备份 `.env`；双层 nginx 已 reload。
- 线上核对：`IMAGE_TAG=0.7.12`、backend healthy、公网 `/api/health` ok、
  线上入口 `assets/index-EGM0VR1M.js` 与本机 dist 一致、容器内无「 2」后缀重复资源。
- 缓存清理：本地删 `node_modules/.vite` 与 `public/vendor` 后重启；线上清两层 nginx 临时目录并 reload，
  `docker image prune` 回收 291MB，磁盘 31%。

## 遗留

- 风格仍为固定目录，暂不支持用户自定义一词/一句话风格（可后续加 `styleNote`）。
- 参考图只用于风格分析，不参与内容重绘；多图时只取前两张。
- 旧数据库里若节点未保存风格选择，加载后按「有参考图→跟随原片」动态决定，不写回草稿（避免无关改动触发版本冲突）。
- 工作区存在未跟踪的 `frontend/src/components/workbench/GlobalNav 2.vue`（疑似访达复制产生的重名副本），
  非本次改动生成，未处理；提交前注意不要误加。
- `public/vendor` 重复副本问题见 `docs/bugs/bug-2026-11-05-public-vendor-duplicate.txt`，
  已在 `vite.config.js` 加 dev 启动清理插件，需随下一次发布上线。

## 环境注意事项

- 本地后端仍直连生产库（SSH 隧道 15432），本次无迁移。
- `frontend` 目录临时 `npm i --no-save playwright` 用于冒烟，未写入 package.json。
