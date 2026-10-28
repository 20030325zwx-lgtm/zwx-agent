# 会话摘要：阶段 A 桌面运行时验证 + 工作台 B1 骨架（2026-10-29）

接续 `session-2026-10-28-ai-workbench-handoff.md`（其头部已标注被本文件取代）。用户确认按 A→B→C 顺序推进，并追加三条硬性要求：UI 必须美观新颖；功能代码模块化、适时抽公共依赖；前端样式管理精简高效、拒绝冗余。

## 改动内容

### 阶段 A：桌面运行时技术验证（全部实测通过）

- 验证结论与模型契约文档：`docs/design/2026-10-28-desktop-phase-a-runtime-model-contract.md`（含全部数据）。
- PostgreSQL：Zonky `embedded-postgres-binaries-darwin-arm64v8` 18.6.0（Maven Central，SHA1 校验一致；国内走阿里云镜像快，GitHub 直连极慢）。initdb/启动成功，新集群 39MB；捆绑包 392MB、去重 dylib 后约 214MB。
- pgvector 0.8.7 源码编译成功（仿造 pg_config + PG_CPPFLAGS + 源码树内 `Makefile.pgxs` 包装），`CREATE EXTENSION` 与 vector 类型读写正常。三个必踩坑：服务端 sharedir 是 `share/postgresql/`（Debian 风格）、DLSUFFIX 是 `.dylib`、Zonky 包无 psql/pg_config/头文件。
- JRE：jlink（java.se,jdk.crypto.ec,jdk.unsupported）59MB，3 秒产出。
- 空库冷启动：jlink JRE + prod profile + 显式 datasource 参数，**4.566 秒**启动，health ok，Flyway V1~V3 全过、26 张表、`agent_knowledge_vector` 建表成功；无任何模型 Key/OSS/MinIO/Docling/搜索配置不阻塞。RSS 约 297MB。
- 测试全部清理干净（测试后端已停、测试 PG 已停）；`application-local.yml` 未读取密钥值、未修改。

### 阶段 B1：工作台前端骨架（已可用、已验证）

- 组件库：`ant-design-vue@4.2.6` + `ant-design-x-vue@1.6.0` 精确版本（注意包名是**无 scope 的 `ant-design-x-vue`**，npmmirror 上 `@ant-design/x-vue` 404）；vue 声明固定为 3.5.14。
- 设计体系（新增 `frontend/src/design/`）：`tokens.js` 是唯一颜色数据源（light/dark 双主题，CSS 变量 `--wb-*` 与 antd token 同源）；`apply-tokens.js` 注入变量、管理 light/dark/system 三态并持久化；`base.css` 只放全局补充规则。**组件样式一律 scoped + var(--wb-\*)，禁止硬编码色值**——旧组件（--sk-* 体系）暂不动，后续逐个迁入。
- 工作台骨架：`components/workbench/WorkbenchShell.vue`（导航+内容槽）、`GlobalNav.vue`（60px 竖向 rail，只挂已上线能力：工作台/任务/知识库/设置/主题切换/用户徽标，带 tooltip 与激活态）。
- 工作台首页：`views/Workbench.vue` + `components/workbench/WorkbenchComposer.vue`。问候语+日期、大输入卡（antd Select 关联项目 + 默认模型徽标 + 开始任务）、三个示例 chip（只填输入框不自动调用）、继续工作（真实会话）/最近运行（真实 runs）双栏。数据接口全走现有 `api/index.js`。
- 路由：`/workbench` 为默认入口；`/` 与 `/home` 重定向到 `/workbench`；model-settings 等旧路由 URL 不变（仍挂 WorkspaceLayout）；`/super-agent` 不变。
- 草稿交接：首页「开始任务」→ `createManusConversation` → 深链 `/super-agent?conversationId=…`，草稿经 sessionStorage（key `aster.workbench.draft`）由 SuperAgent 挂载时消费（`composerRef.fill()`），**不自动发送**；SuperAgent 仅加了 nextTick 导入与 8 行消费逻辑，TaskComposer 未改（复用其已暴露的 fill/focus）。

## 验证结果

- `npm run build` 通过（15.4s）。
- 浏览器实测（IAB，admin 账号）：`/`→`/workbench` 重定向 ✓；深色/浅色主题切换与持久化 ✓（截图检查布局）；示例 chip 填充 ✓；「开始任务」全闭环（建会话→跳转→草稿填入任务输入框）✓；`/model-settings` 旧页面回归正常 ✓。
- 验证过程中发现并修复 2 个问题：① `listModelSettings()` 返回 `{models:[...]}` 而非数组，`.find` 抛错导致 loading 卡住；② 模板用 `<a-button>`/`<a-select>` kebab 标签但未全局注册 antd，渲染成无样式元素——script setup 下必须显式 import 后用 PascalCase。
- 验证残留：admin 账号下产生了一个空会话 `b2e9b8e2-7901-4dcd-9039-0688fe89396a`（闭环测试产物），可在界面删除或忽略。

## 遗留事项

- B 阶段后续：设置框架重做（供应商/模型目录页）、旧首页 `Home.vue`（1072 行）与 `WorkspaceLayout` 下线、SuperAgent 重组进 Workbench Shell、旧组件 `--sk-*` 样式迁入 `--wb-*` 令牌。
- C 阶段：按 `2026-10-28-desktop-phase-a-runtime-model-contract.md` 第 7 节实施 Provider/Model/Binding；注意图节点直接注入 ChatModel bean 绕过用户解析的技术债。
- D 阶段：向量库改可选装配（`@ConditionalOnProperty`）、desktop profile、main.cjs 集成托管 PG、回环认证（当前 Electron 无本地鉴权）、`prepare-backend.sh` 把 `./mvnw` 改 `mvn`。
- 本轮前后端业务代码改动均未提交 Git；设计文档同样未提交。

## 环境注意事项

- 严格保护 `application-local.yml`（本轮未触碰）。
- 后端/前端 launchctl 服务全程未重启；前端 dev server(3000) 热加载了新代码，生产 jar 未重建（本轮无后端代码改动）。
- 测试目录残留 `/tmp/aster-pg-data`（已 stop）、`/tmp/zonky-pg-extract`、`/tmp/postgresql-18.6`、`/tmp/aster-verify-runtime`（jlink 产物 59MB），可复用也可删除；重跑验证参照设计文档第 3 节步骤。
