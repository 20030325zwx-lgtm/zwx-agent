# 会话摘要：2026-09-09 markdown 技能仓库（批次 B）

> 状态：已完成、已打包、后端已重启（health ok）、前端 build 通过、端到端验证通过。未提交 git（沿用仓库惯例等用户指示）。
> 实施 design/plans/2026-09-08-memory-skills-workspace.md §3.2 批次 B；同时推进 plans/08 阶段 3 的"技能注入"地基。

## 改动内容

1. **`skills/SkillRepository`**（新增，@Component）：
   - 启动（@PostConstruct）扫描 `app.skills.dir`（默认 `skills`，相对后端工作目录）下 `.md` 文件，解析 front-matter（自写轻量解析：`key: value` + `[a, b]` 数组 + 引号剥离，无新依赖）+ 正文作为 instruction；
   - 必填字段 id/name/trigger/agents 缺一即跳过该文件（warn 不阻断）；description 可选（缺省取正文第一行 120 字符）；id 重复先到先得；目录按文件名排序、catalog 按 id 排序保证确定性；
   - **空目录 / 目录不存在 / 解析全失败 → 回落内置 web-research**（FALLBACK_WEB_RESEARCH 常量，镜像原硬编码值），启动绝不因技能文件挂掉；
   - `reload()` 公开方法供热加载；日志打印加载结果。
2. **`skills/BuiltInSkill` record 扩展**：新增 `tools`（List<String>）/`instruction`/`source`（"markdown"/"builtin"）三组件，保留 5 参兼容构造器（source=builtin）。
3. **`skills/BuiltInSkillRegistry` 重接线**：
   - @Autowired 构造器改 3 参（tools, configService, skillRepository）；1 参测试构造器内部用不存在目录的 SkillRepository（强制回落）；
   - `availableFor` 改为逐技能判定：agentKeys 包含 + 租户开关 + **仅声明 webSearch 工具的技能受本轮联网开关限制**（纯提示技能不受限）；`toolCallbacksFor` 改为可用技能声明工具的并集（LinkedHashSet 按 callback 身份去重），webSearch → travelTools；
   - `catalogWithConfiguration` 带 source。
4. **`skills/SkillConfigurationService.save` 签名变更**：`save(tenantId, agentKey, knownIds, enabledIds)`——原来硬编码 `Set.of("web-research")` 落库，导致新技能无法被租户开关管理（无行=默认开），现按动态 catalog 全量落行。
5. **`skills/SkillPromptBuilder`**：模板与规则文字不变；定义行后追加 markdown 技能的 instruction（`指引：` 缩进段落）；内置技能 instruction 为空时输出与旧版逐字节一致。
6. **`SkillCatalogItem`** 加 `source` 组件（保留 5 参兼容构造器）。
7. **API**：`POST /ai/skills/reload?agentKey=`（ADMIN，reload 后返回该 agent 新 catalog）；`GET /ai/skills/catalog` 自然带 source。
8. **`skills/web-research.md`**（仓库根新增）：内置技能迁移为文件，agents/tools/trigger/description 与原硬编码完全一致，正文 3 条操作指引。
9. **application.yml**：`app.skills.dir: ${APP_SKILLS_DIR:skills}`（加在 app: 下 workspace 之后，已确认无撞键）。
10. **前端 SkillSettings.vue**：技能行加来源徽章（markdown/内置）。
11. **测试**：`SkillRepositoryTest` 9 个（front-matter 数组与正文/引号剥离/description 回落正文首行/缺必填拒绝/缺标记拒绝/坏文件跳过好文件加载/id 重复先到先得/空目录与不存在目录回落/reload 热加载）；`BuiltInSkillRegistryTest` 6 个（纯提示技能不受联网开关限制/webSearch 技能受开关+回调授予/prompt 含 markdown 指引/catalog 带 source/空仓库回落内置/未知技能校验拒绝）。

## 验证结果（端到端）

- 单测：全量 108 个，0 失败，**5 个 Error 为文档记载的存量环境问题**（LoveAppTest 4 个 DB FK + PgVectorVectorStoreConfigTest 1 个），与改动前基线完全一致。
- 启动日志出现 `[skills] 已加载 1 个 markdown 技能：[web-research]`。
- `GET /skills/catalog?agentKey=love`：web-research，`source=markdown`。
- **热加载验收**：放入 `skills/echo-note.md`（agents:[love], tools:[]）→ `POST /skills/reload` 立即返回 2 个技能（echo-note 的 description 自动取正文首行），**零代码改动**；删除文件再 reload 恢复 1 个。
- love SSE 对话正常（thinking/正文流/trace/references/[DONE]），SkillPromptBuilder 注入链路无回归。
- 测试数据已清理：测试技能文件已删、测试会话已删、临时测试账号已从 app_user 删除。
- `mvn -DskipTests package` + launchctl 重启 health ok；前端 `npm run build` 通过。

## 环境注意事项

- **OrbStack（非 Docker Desktop）当日曾整体卡死**：5432/9000 端口 TCP 可通但容器无响应、`docker` CLI 挂起、测试 Flyway 连库报 08001；后端因存量连接池假活。处理：`osascript quit app "OrbStack"` + `open -a OrbStack`，容器自动拉起后恢复。遇到"后端 health ok 但新连接全失败"先怀疑这个。
- 全量测试跑一轮约 5~8 分钟（MultiQueryExpanderDemoTest 单个约 90s），排超时至少给 20 分钟。
- `target/classes` 里曾有 Finder 复制出的 `XXX 2.class` 垃圾文件被组件扫描捡到（bean 定义来自 "AgentHookPipeline 2.class" 等），src 无污染；下次 `mvn clean` 会消掉。
- 临时提权验证法：`POST /auth/register` 建号 → `docker exec yu-ai-agent-postgres psql -U <user> -d zwx_agent -c "UPDATE app_user SET role='ADMIN' ..."` → 重新登录，用完删号。
- `application-local.yml` 未触碰。

## 遗留事项

- 批次 C：分层记忆（V7 事实表 + MemoryService + 压缩/提取/注入 hook，提取开关默认关）——plans/2026-09-08 §3.3；
- plans/08 阶段 3 剩余：ToolGuardHook、记忆压缩（=批次 C 的 L1）；StepMonitoringHook 迁移（阶段 2 遗留，需逐字节对照 SSE）；
- plans/07 第二批：hash 去重拦截 + publish/rollback/versions/retire 四个端点；
- 新技能要用新工具（webSearch 之外的 ToolCallback）仍需改 BuiltInSkillRegistry 的映射（当前仅 webSearch），做 SaveSkillTool/自我进化时一并考虑。
