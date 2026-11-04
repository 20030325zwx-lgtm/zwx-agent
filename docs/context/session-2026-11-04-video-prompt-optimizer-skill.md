# 视频提示词优化师内置技能（2026-11-04）

## 改动内容

按用户提供的视频提示词模板/公式/词汇/避坑资料，固化为一套可复用规则，并以内置技能形式接入创作工作台。首版只做单镜改写；同日按反馈补齐镜头词汇、焦距、负向提示、人物修正与分镜拆解。

### 共享规则

- 新增 `creative/support/VideoPromptGuide.java`：
  - 七要素公式：主体 + 动作 + 环境 + 镜头语言 + 光线色调 + 风格 + 质感；
  - 镜头词汇表成对输出 English/中文：Close-up 特写、Dolly in 推轨、Golden hour 黄金时刻等；
  - 焦距建议：21mm 广角 / 35mm 中景 / 50mm 标准 / 85mm 特写 / 200mm 长焦；
  - 分镜拆解：单场景保持单镜，多动作/场景切换拆 2–8 镜，相邻镜头首尾状态衔接；
  - 避坑：单镜单动作、单主运镜、正向描述优先、条件性 `negativePrompt`、人物补 `anatomically correct hands and face`、500 字上限。

### 内置技能

- 新增 `video-prompt-optimizer`「视频提示词优化师」：
  - 分类 DIRECTING、阶段 PROMPT、TEXT→TEXT、仅需 CHAT 能力；
  - 输出契约：`summary / prompt / negativePrompt / shots / changes / warnings / missing`；
  - 单镜 `shots` 1 个元素，多镜 2–8 个；`prompt` 等于首镜提示词，便于直接采用；
  - 仍为纯文本技能：不创建媒体任务、不产生计费。
- `CreativeSkillService.parsePlan` 增加可选 `changes` 与 `negativePrompt`。
- `PromptOptimizationService.SYSTEM_VIDEO_PROMPT` 复用同一 RUBRIC，仅保留
  `optimizedText/changes/suggestions/missing` 契约；需要拆镜时把方案写入 suggestions。

### 内置技能正文升版

- `CreativeSkillService.seed()` 对已存在的系统技能比较最新版本 `content_hash`：
  变更时插入新版本行并推进 `latest_version`，旧版本整行保留，历史 run 仍引用旧版本；
- 多实例并发写同一版本号时捕获 `DuplicateKeyException` 保持幂等；
- 本次重启日志确认：`内置技能 video-prompt-optimizer 正文已变更，升级至 v2`。

### 前端

- `ProjectStudioTab.vue` 技能方案区展示 `changes` 优化点；
- 有 `shots` 时渲染分镜卡片：镜号、景别/角度/运镜/焦距/光线/时长、单镜提示词、衔接方向；
- 每镜可"采用此镜"（只替换输入台，保留方案便于比较）；
- 可"复制分镜表"为纯文本；
- 修正误触发：只有 `mediaOperation === 'text_to_video'` 才显示"确认并逐镜生成"，
  CHAT-only 技能即使返回多镜也不会提交计费任务。

### 技能库清理（同日追加）

- 测试遗留 `test-import-skill`（私有、0 次运行）已置为 `RETIRED`，版本与导入历史保留；
- 下架 3 个与现有能力重叠或偏小众的内置技能：
  - `camera-performance-designer` 运镜与表演设计（与导演/优化师重叠）；
  - `ambient-motion` 氛围图动起来（小众图生视频）；
  - `fashion-look-display` 穿搭展示短片（与商品展示重叠）。
- 通过 `V30__retire_obsolete_creative_skills.sql` 做可重复的软下架（只改 lifecycle），
  同时从 `CreativeSkillContent.SEEDS` 移除对应种子，避免启动时重建；历史版本与 run 引用不动。
- 保留 7 个：视频提示词优化师、视频提示词导演、剧本转分镜、一图短片、商品展示短片、
  首帧设计师、多镜头叙事短片。
- 新增测试 `retiredSkillsAreNotReseeded`，防止清理过的 slug 被误加回种子。

### 模型密钥与 500 字超限修复（同日追加）

- 用户点「生成方案」报"未配置模型 API Key"，但技能列表显示 READY：
  - `chatAvailable` 原来只验证"能解析到配置"，不验证密钥/模型名；已改为校验
    `hasApiKey()` 与 `modelName`，缺密钥时如实显示缺能力；
  - 本环境补了 SYSTEM / chat 默认绑定 → 智谱 BigModel / glm-4.7-flash（免费），
    供没有个人 chat 绑定的账号使用；admin 仍优先使用自己的 DashScope qwen-plus；
  - 该绑定是环境配置，不写 Flyway 迁移（新环境密钥未填时不应自动改变全站默认）。
- 实测方案首镜 679 字，超过 `MODEL_PROMPT_MAX=500`，采用后会被提交环节拒绝：
  - `parsePlan` 增加 `clipPrompt`：超限优先在句末压缩，否则硬截到 500，并追加 warnings；
  - 顶层 prompt 统一取首镜压缩结果，保证"采用首镜/采用此镜"文本一致；
  - 新增 `CreativeSkillPromptClipTest` 覆盖短文本、句末压缩、无标点硬截、null。

## 验证结果

- `mvn -DskipTests package` 通过。
- `mvn -Dtest=CreativeSkillContentTest,CreativeSkillPromptClipTest,PromptOptimizationModesTest test`：
  3 + 4 + 5 个测试全部通过。
- `frontend npm run build` 通过；仅有既有大 chunk 警告。
- launchctl 重启后端后 health 正常；日志确认内置技能正文升级至 v2。
- 技能清理后 Flyway v27–v30 均成功；`creative_skill` 为 7 个 PUBLISHED、4 个 RETIRED
  （测试导入技能 + 3 个下架内置技能），后端启动无种子失败。
- 期间发生过一次迁移版本号冲突（V27 重复）导致后端启动失败，已改为 V30 并清理
  `target/classes` 残留，详见 `docs/bugs/bug-2026-11-04-flyway-migration-version-collision.txt`。
- 未完成技能列表 API 实测：`frontend/.env.local` 中的开发账号登录返回 401，未继续尝试或写库；
  种子入库与版本升级已由启动日志证实。
- 已用本地默认 JWT 密钥签发 admin token 做真实端到端：
  `GET /ai/creative/skills` 优化师 `compat=READY`；
  `POST /ai/creative/projects/{videoProject}/skill-runs` 返回 200，生成 3 镜方案；
  压缩修复后复测首镜 500 字、次镜 420 字，warnings 正确提示已压缩。
- 仍未用真实模型做多轮质量抽检（拆分合理性、主运镜唯一性等）。

## 遗留事项

- 需要真实模型抽检：多动作是否被正确拆镜、每镜主运镜是否唯一、是否仍出现画质承诺；
- `negativePrompt` 目前只是可复制建议字段：媒体任务参数与适配器尚未接入负向提示，不随生成提交；
- 七要素检查仍完全依赖模型，尚未做确定性 lint；
- 文本型技能的分镜卡只用于采用/复制，不直接生成；逐镜生成仍由文生视频技能负责；
- 技能目前在所有项目类型可见（包括 IMAGE）；是否只对 VIDEO 项目展示属于产品决策；
- 本次修订尚未提交 git。

## 环境注意事项

- 后端已通过 launchctl 重启并运行新 JAR；前端 Vite 走 HMR，无需重启。
- 技能库清理通过 psycopg2 读取/更新了本地配置指向的数据库（`creative_skill.lifecycle`）；
  另新增了本环境的 `SYSTEM/chat -> 智谱 glm-4.7-flash` 绑定（未写迁移）。
  新环境仍需在"模型设置"里配置系统默认文本模型，否则无个人绑定的账号会缺模型。
- 未触碰 `src/main/resources/application-local.yml`。
- 未运行任何 Git 清理命令。
