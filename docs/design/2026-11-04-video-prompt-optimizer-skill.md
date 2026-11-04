# 视频提示词优化师：内置技能与固化规则

日期：2026-11-04  
状态：已实施（P0）  
范围：AI 创作工作台中的视频提示词优化能力；不涉及新的媒体生成链路

## 1. 背景与定位

视频生成质量对提示词结构高度敏感。当前工作台已有两处文本改写入口：

- 项目「快速生成」与镜头检查器的「优化提示词」按钮（`PromptOptimizationService`，`VIDEO_PROMPT` 模式）；
- 内置技能「视频提示词导演」（从目标/风格生成一条新提示词）。

两者都缺一个面向"用户已经写好一段提示词，需要按固定标准体检并改写"的入口。本设计新增内置技能：

**视频提示词优化师**（slug `video-prompt-optimizer`）

- 输入：用户已写好的视频提示词，可附带风格、时长、平台等约束；
- 输出：诊断结论 + 按七要素改写后的提示词 + 分镜拆解（2–8 镜）+ 实际改动清单 + 冲突/待补项；
- 边界：只做文本改写，不创建媒体任务、不产生计费；文案始终保留原文。

它与「视频提示词导演」的区别：导演面向"从想法开始"，优化师面向"已有稿件的标准化改写"。

## 2. 固化规则

规则集中在 `creative/support/VideoPromptGuide.RUBRIC`，内置技能与「优化提示词」按钮共用，避免两套口径。

### 2.1 七要素公式

```text
[主体] + [动作] + [环境] + [镜头语言] + [光线色调] + [风格] + [质感]
```

| 要素 | 要求 |
|---|---|
| 主体 | 外貌、服装、年龄、表情、关键道具；只写画面可见信息 |
| 动作 | 一个核心动作，写明部位、方向、速度、幅度；多动作建议拆镜 |
| 环境 | 地点、天气、时间、背景细节，与主体动作发生关系 |
| 镜头语言 | 景别 + 角度 + 一种主运镜 + 速度；可给等效焦距 |
| 光线色调 | 光源方向与性质 + 主色调；只用一个主光逻辑 |
| 风格 | 只选一种整体风格；风格名可用常用英文 |
| 质感 | 胶片颗粒、浅景深、自然皮肤质感等可验证介质词 |

### 2.2 镜头词汇表

成对使用"English 中文"，便于模型识别：

- 景别：Close-up 特写 / Medium close-up 近景 / Medium shot 中景 / Wide shot 全景 / Extreme wide 大远景
- 角度：Eye level 平视 / Low angle 仰拍 / High angle 俯拍 / Over-the-shoulder 过肩 / POV 主观视角
- 运镜：Static 固定 / Dolly in 推轨 / Dolly out 拉轨 / Truck 横移 / Pan 摇镜 / Tracking 跟拍 / Aerial 航拍 / Orbit 环绕 / Steadicam 斯坦尼康 / Handheld 手持
- 光线：Golden hour 黄金时刻 / Blue hour 蓝色时刻 / Rim light 轮廓光 / Volumetric 体积光 / Soft light 柔光 / Practical light 实用光源 / Top light 顶光
- 风格：Cinematic 电影写实 / Documentary 纪录片手持 / Anime 动画 / Cyberpunk 赛博朋克 / Stop motion 定格

焦距建议：21mm 广角建立空间 / 35mm 中景 / 50mm 标准 / 85mm 特写 / 200mm 长焦压缩。

### 2.3 分镜拆解

- 原文只有一个连续动作或一个场景：保持单镜；
- 原文包含多个动作、场景切换或时间跳跃：按叙事节拍拆成 2–8 镜，每镜一个核心动作、一种主运镜；
- 相邻镜头遵循"上一镜结束状态 = 下一镜起始状态"，写明衔接方向；
- 不为凑数量拆镜，信息不足时宁可少拆并列入待补。

### 2.4 避坑清单

- 一个镜头只给一个核心动作，不串联多个动作；
- 一个镜头只用一种主运镜；
- 负向约束优先转成正向描述；目标模型支持负向提示时单独输出 `negativePrompt`（不要镜头晃动、不要镜头光晕、不要画面畸变），不混进正向正文；
- 人物手部/面部特写可在提示词末尾补 `anatomically correct hands and face`，只作为一句修正；
- 保留用户已给出的品牌、人物、台词、风格与参考图约束；
- 只描述画面可见内容，声音未接入就不写声音；
- 每条提示词正文不超过 500 字。

### 2.5 与参考资料的差异

参考资料将"4K、photorealistic、film grain"列为画质项。本实现保留 `photorealistic`、`film grain` 这类**视觉介质词**，但明确禁止 `8K`、`超高清`、`大师级` 等无法由模型保证的画质承诺，与产品原则「不伪造模型能力」一致。

## 3. 实现

### 3.1 后端

- 新增 `creative/support/VideoPromptGuide.java`：共享 `RUBRIC` 常量；不含输出契约。
- `CreativeSkillContent` 新增种子 `video-prompt-optimizer`：
  - `category=DIRECTING`、`stage=PROMPT`、`inputTypes=TEXT`、`outputTypes=TEXT`、`requiredCapabilities=[CHAT]`；
  - 正文 = `RUBRIC` + 优化师专用输出契约
    （`summary/prompt/negativePrompt/shots/changes/warnings/missing`）；
  - 单镜方案 `shots` 放 1 个元素，多镜方案 2–8 个；`prompt` 等于首镜提示词，便于工作台直接采用。
- `CreativeSkillService.parsePlan` 增加可选字段 `changes`（实际改动）与 `negativePrompt`；
  并对 `prompt` / `shots[].prompt` 做提交长度兜底：超过 `MODEL_PROMPT_MAX=500` 时在句末压缩
  （无标点则硬截），同时追加 warnings，保证方案文本可直接提交。
- `PromptOptimizationService.SYSTEM_VIDEO_PROMPT` 改为复用 `RUBRIC`，仅保留自己的
  `optimizedText/changes/suggestions/missing` 输出契约；需要拆镜时把方案写入 `suggestions`。

### 3.2 前端

- `ProjectStudioTab.vue` 技能方案区展示 `plan.changes`（本次优化点）；
- 有 `plan.shots` 时渲染分镜卡片：镜号、景别/角度/运镜/焦距/光线/时长、单镜提示词、衔接方向；
- 每镜提供"采用此镜"（只替换输入台，保留方案便于比较）；
- 提供"复制分镜表"，生成纯文本便于带到外部工具；
- 文本型技能的多镜方案**不显示**"确认并逐镜生成"：前端以 `mediaOperation === 'text_to_video'`
  判断是否允许逐镜提交，避免 CHAT-only 技能误触发计费；
- 技能库通过现有种子自动出现，无需新页面。

### 3.3 数据与版本

无新增表、无迁移。技能行由启动时 `@PostConstruct seed()` 插入；新 slug 在已有环境也会自动补齐。

内置技能正文变更时，`seed()` 会比较最新版本行的 `content_hash`，不同则插入 **新版本行**并推进
`latest_version`，旧版本整行保留、历史 run 仍引用原版本。因此本次改版在已部署环境会自动从 v1 升到 v2，
不需要手工改库。

## 4. 状态与安全边界

- 方案生成只调用文本模型，不创建 `MediaJob`，不产生计费；
- 技能运行失败保留 run 记录，可在原 run 重试方案；
- 用户确认/采用前不修改任何已确认内容；
- `negativePrompt` 是可复制建议字段：当前媒体任务参数未接入负向提示，不会随生成提交；
- 分镜提示词由服务端统一压缩到 `MODEL_PROMPT_MAX=500` 以内，并在 warnings 中说明；
- 技能兼容状态以"能实际建模"为准：解析到绑定但缺 API Key/模型名时显示缺能力，而不是 READY；
- 不新增跨项目、跨用户读取；素材引用规则不变。

## 5. 验收

1. 技能库出现「视频提示词优化师」，分类"导演与分镜"、阶段"提示词"、兼容状态 READY；
2. 在项目「快速生成」中粘贴一段粗糙提示词，生成方案后能看到诊断、改动清单、首镜改写稿与分镜拆解；
3. 分镜卡片展示景别/角度/运镜/焦距/光线/时长与单镜提示词，可"采用此镜"或"复制分镜表"；
4. 文本型技能即使返回多镜，也不出现"确认并逐镜生成"；文生视频技能仍保留该按钮；
5. 原文与草稿不被覆盖，改写只进入输入台；
6. 已部署环境重启后，内置技能正文自动升级到新版本，历史 run 仍引用旧版本；
7. 现有「优化提示词」按钮行为不回退，输出仍为结构化 `optimizedText/changes/suggestions/missing`。

## 6. 后续

- 需要真实模型抽检改写质量，重点看：多动作是否被正确拆出、每镜主运镜是否唯一、是否仍出现画质承诺；
- `negativePrompt` 目前只能复制到外部工具使用；若要在本工作台生效，需要媒体模型参数与适配器逐项验证后再接入；
- 可考虑把七要素检查做成确定性 lint（不依赖模型），在提交前提示缺项；
- 多参考图、音频、延长等能力仍待逐模型验证，不在当前版本提前承诺。
