# 视频提示词优化师内置技能（2026-11-04）

## 改动内容

按用户提供的视频提示词模板/公式/词汇/避坑资料，固化为一套可复用规则，并以内置技能形式接入创作工作台：

- 新增 `creative/support/VideoPromptGuide.java`：七要素公式（主体+动作+环境+镜头语言+光线色调+风格+质感）、镜头词汇表、避坑清单与 500 字上限；不含输出契约，供技能与按钮共用。
- 新增内置技能 `video-prompt-optimizer`「视频提示词优化师」：
  - `CreativeSkillContent` 新增种子，分类 DIRECTING、阶段 PROMPT、TEXT→TEXT、仅需 CHAT 能力；
  - 正文 = 共享 RUBRIC + 专用输出契约（summary/prompt/changes/warnings/missing）；
  - 明确禁止输出 `shots`，避免前端误走逐镜确认（该技能没有 TEXT_TO_VIDEO 能力）。
- `CreativeSkillService.parsePlan` 增加可选 `changes` 字段（实际改动清单），其他技能不返回时为空数组。
- `PromptOptimizationService.SYSTEM_VIDEO_PROMPT` 改为复用同一 RUBRIC，仅保留 `optimizedText/changes/suggestions/missing` 输出契约，使工作台「优化提示词」按钮与技能同口径。
- `ProjectStudioTab.vue` 技能方案区展示 `changes` 优化点（新增 `.skill-changes` 样式，使用既有 `--wb-*` token）。
- 新增设计文档 `docs/design/2026-11-04-video-prompt-optimizer-skill.md`，并加入设计索引。
- 新增测试 `CreativeSkillContentTest`：锁定优化师种子字段、共享公式、禁止 shots、slug 唯一。

与参考资料的刻意差异：保留 photorealistic / film grain 等视觉介质词，但禁止 8K、超高清、大师级等无法保证的画质承诺，符合「不伪造模型能力」原则；"画质"槽位改为"质感"。

## 验证结果

- `mvn -DskipTests package` 通过（首次 jar 组装出现一次 `VerificationStatus.class` 缺失的瞬时错误，重跑即成功；类文件实际存在，疑为 target 并发写入残留）。
- `mvn -Dtest=CreativeSkillContentTest,PromptOptimizationModesTest test`：2 + 5 个测试全部通过。
- `frontend npm run build` 通过；仅有既有大 chunk 警告。
- 通过 launchctl 重启后端（新 JAR），health 正常；启动日志确认：
  `[creative-skills] 已内置技能 video-prompt-optimizer（视频提示词优化师）`。
- 未完成技能列表 API 实测：`frontend/.env.local` 中的开发账号（5 字符用户名/16 字符密码）登录返回 401，未继续尝试或写库；种子插入已由启动日志证实。

## 遗留事项

- 未用真实模型抽检改写质量（是否真的收敛为单动作/单运镜、是否仍出现画质承诺）；需要配置可用文本模型后手工走一次方案生成。
- 七要素检查目前完全依赖模型，尚未做确定性 lint；后续可在提交前增加缺项检查。
- 技能目前在所有项目类型都可见（包括 IMAGE）；若需要只对 VIDEO 项目展示，应在技能列表或前端按项目类型过滤，属于产品决策。
- 修改既有内置技能正文仍不会自动升版（现有约定：正文升版走显式版本流程）；本次用新 slug 规避，旧技能正文未动。
- 未提交 git。

## 环境注意事项

- 后端已通过 launchctl 重启并运行新 JAR；前端 Vite 走 HMR，无需重启。
- 后端连接的实际数据库未在本次确认（历史摘要提示可能是 15432 SSH 隧道远端库）；本次未直接读写数据库、未调用计费模型。
- 未触碰 `src/main/resources/application-local.yml`，未运行任何 Git 清理命令。
