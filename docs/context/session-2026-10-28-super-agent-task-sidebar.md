# 任务页侧栏重构（Codex / ZCode 式管理）

日期：2026-10-28
范围：仅前端（`frontend/src`），后端无改动。

## 改动内容

- `components/ConversationSidebar.vue` 重写为任务管理栏：
  - 去掉与页面头部重复的「超级智能体」品牌头，顶部只保留「任务 + 数量 + 收起」；
  - 常驻搜索框替代搜索图标开关，支持 ⌘K / Ctrl+K 聚焦，Esc 清空；
  - 顶部主入口从「新建任务」改为「添加工作区」（任务统一由工作区分组行的「+」新建，二级动作不再抢占一级位置）；
  - 任务按工作区分组，分组头可折叠（`aster.tasks.collapsedGroups` 持久化）；
  - 分组行的「+」常驻可见（悬停加深），直接在该工作区新建任务（`create-in` 事件）；
  - 任务行悬停时时间让位给删除按钮，减少常驻噪声；
  - 工作区弹窗改用 `--wb-*` 设计令牌，深色模式一致。
- `views/SuperAgent.vue`：
  - 头部不再重复品牌与 Agent 胶囊，改为显示「当前会话标题 + 所属工作区」；
  - 移除「返回主页」文字按钮（全局导航已提供「工作台」）；MCP 入口收回为右侧胶囊按钮；
  - 新增任务栏折叠开关（⌘B），折叠状态存 `aster.super.sidebar.collapsed`；
  - 移动端（≤720px）侧栏为抽屉 + 遮罩，Esc 可关闭；
  - 画布、模板卡片、横幅、空状态、Inspector 轨道改用 `--wb-*`。
- 主题统一：`ConversationSidebar`、`SuperAgent`、`TaskComposer`、`AgentCompanion`、`ChatRoom`、`RunInspector` 中的 `--sk-*` / `--zwx-*` 全部迁移到 `--wb-*`，深色模式下正文、气泡、输入框、弹窗均可读。

## 验证结果

- `npm run build` 通过（仅有原有的 chunk 体积提示）。
- 浏览器（IAB，1360×940）实测：
  - 收起/展开任务栏正常，刷新后状态保持；
  - ⌘K 聚焦搜索并过滤任务，清空恢复；
  - 分组折叠后刷新仍保持；
  - 行内删除弹出确认框，删除后自动切换会话；
  - 新建任务 → 发送消息 → 标题自动更新，助手正文深色模式可读；
  - 深色模式截图确认侧栏、画布、工作区弹窗、输入框、气泡一致。
- 测试产生的临时会话已删除，保留用户原有 1 个会话；主题已恢复为 light。

## 遗留事项

- `ChatRoom` 的引用详情弹窗（`.citation-modal`）仍固定白底，按「文档阅读面板」保留，未随主题切换。
- `components/HomeButton.vue` 目前已无引用，暂未删除，留作其他页面复用。
- 移动端抽屉只做了 CSS 断点实现，未在真实移动设备复测。

## 环境注意事项

- 后端 `8123`、前端 `3000`、Docker PostgreSQL 均未改动；本次只涉及前端样式与交互。

## 追加调整（同日）

- 按反馈移除顶部「新建任务」大按钮：任务创建统一收敛到工作区分组行的「+」。
- 原底部的「添加工作区」上移到该位置，搜索框下移到它下面；分组「+」由 hover 才显示改为常驻 55% 透明度、悬停加深。
- 无任何分组时仍保留空状态的「新建第一个任务」入口，避免新用户无路可走。
- 实测：连续刷新两次不会新增空对话；测试产生的空对话已清理，当前保留 1 个会话。

## 追加调整（同日）：品牌标记

- 卡通机器人图标替换为几何四角星标记，详见 `docs/design/2026-10-28-brand-mark.md`。
- 新增 `AsterMark.vue`；`AsterAvatar`、全局导航品牌位、登录徽标全部切到新标记；新增 `--wb-mark-*` 令牌（深色模式用内描边而不是反色方块）。
- 补齐 favicon：原 `index.html` 指向不存在的 `favicon.ico`，现改为 `favicon.svg` + `apple-touch-icon.png`，并补 `theme-color`。
- 桌面 `desktop/assets/icon.svg` 重绘为 1024 墨色圆角方块 + 白星，`generate-icon.sh` 重新生成 `icon.icns`（被 gitignore，打包时再生成）。
- 验证：构建通过；浅色/深色任务页截图正常；16px favicon 可辨认。临时预览页已删除。

## 追加调整（同日）：桌面端与网页端一致性

- 桌面端静态资源服务改用固定端口 47821（占用时回退随机端口），解决 localStorage 按 origin 隔离导致的主题/侧栏状态每次启动重置；详见 `docs/bugs/bug-2026-10-28-desktop-static-port-localstorage.txt`。
- 用当前前端重新执行 `build:web` + `prepare:assets`，`desktop/app` 已与网页端同源产物；差异仅 `base: './'`（相对路径），CSS/JS 内容一致。
- 通过本地静态代理（桌面产物 + /api 转发 8123）在浏览器登录并对比：登录页、任务页、侧栏、图标、浅/深色表现与 3000 一致。
- 顺带把桌面诊断页按钮的旧紫色换成品牌蓝。

## 追加调整（同日）：桌面安装包重建

- `desktop/package.json` 的 `start` 改为先 `build:web` 再 `prepare:assets`，避免改完前端直接 `npm start` 时加载旧的 `frontend/dist`（这是桌面端与网页端样式不一致最常见的来源）。
- 重新打包：`prepare-backend`（mvn package，含 JWT 修复）→ `electron-builder`，产物 `desktop/release/Aster-0.1.2-arm64.dmg`（337MB）与 `Aster-0.1.2-arm64-mac.zip`。
- 打包校验：`Aster.app/Contents/Resources/icon.icns` 与新源文件 SHA 一致；`app.asar` 中 CSS 指纹 `index-Bb4yJS5m.css` 与 `frontend/dist` 一致；`app.asar` 含固定端口 `47821`；`Contents/Resources/server/aster-agent.jar` 为 11:32 新构建。
- 注意：已安装的旧版 App 不会自动更新，需要重新安装这份 DMG 才能看到新界面。

## 追加调整（同日）：去卡通化与打包清理

- `AgentCompanion` 重写为状态条：去掉机器人形象、眨眼/漂浮/音波动画，只保留「标记 + 状态 + 说明」，空闲态不显示。
- `AiAvatarFallback` 去掉 `🤖` / `❤️` emoji；任务页计划/确认横幅的 `📋` / `⏸` 换成 lucide 图标。
- `desktop/package.json` 新增 `clean:release`，`dist:mac` 开头先清空 `release/*.dmg|zip|blockmap|yml`；已手动清理历史 `ZWX Agent-*` 包与重复的 `Aster-...-mac 2.zip`（约 1GB）。
- 重新打包：`desktop/release/Aster-0.1.2-arm64.dmg`（11:47）+ zip；校验 `app.asar` 含 `companion-status`、无 `companion-figure`。

## 追加调整（同日）：桌面回环鉴权修复

- 根因：桌面模式前端同时发送 `Authorization: Bearer desktop-local` 与 `X-Aster-Local-Token`，`JwtAuthFilter` 用 `else if`，JWT 失败后不再尝试回环令牌 → 401 → 落到登录页。
- 修复：回环分支改为独立 `if`（JWT 失败后仍按回环身份放行），有效 JWT 仍优先。
- 验证：临时后端四种头部组合 401/200/200/401；重打包后实机 CDP 确认 `/workbench`、7 个 API 全部 200、无登录页；新包已覆盖 `/Applications/Aster.app`。
- 详见 `docs/bugs/bug-2026-10-28-desktop-loopback-auth-blocked-by-bearer.txt`。

## 追加调整（同日）：输入框权限等级与模型选择

- `TaskComposer` 的原生权限下拉换成富菜单（图标 + 标题 + 说明 + 勾选）；新增模型选择胶囊。
- 模型列表来自 `/settings/catalog`，只列启用且具备 `tools` 能力的模型；默认跟随 chat 绑定，切换后按会话记忆（`aster.chat.model.<id>`）。
- 后端 `/ai/manus/chat` 新增 `modelRowId`，`ModelCatalogService.resolveModelRow` 校验归属与启用状态，闲聊直达与图编排共用；运行通过 `attachToRun` 冻结模型。
- 验证：浏览器实测权限/模型菜单；切到 Qwen Max 发送任务，`agent_run.model_name=qwen-max`，说明覆盖生效。
- 桌面目录补充 `qwen-max`，与网页端可选模型对齐。详见 `docs/design/2026-10-28-composer-permissions-models.md`。

### 桌面端同步

- 重新打包并覆盖安装 `/Applications/Aster.app`（DMG 13:45，337MB，旧包已按 clean:release 清理）。
- 实机验证：无需登录落在 `/super-agent`，输入框同时出现「编辑」和「Qwen Plus」两个胶囊；后端对非法 `modelRowId` 返回 400，合法 id 正常起流。
- 桌面模型目录补充 `qwen-max`，与网页端一致。

## 追加功能（同日）：项目代码工作区

- 新增 `ProjectCodeService` + `ProjectCodeController`：`/ai/manus/conversations/{id}/code/{tree,file,diff}`，支持读目录、读文件、保存（`expectedModifiedAt` 冲突返回 409）、查看 diff。
- 目标目录：有 run checkout 时用该 checkout（Git 项目为 worktree，能看智能体本次改动），否则用绑定项目根目录。
- 新增 `CodeWorkspace.vue` 右侧抽屉：惰性文件树、highlight.js 高亮、行号、编辑保存、改动列表与逐文件 unified diff；会话绑定工作区后顶部出现「代码」入口。
- 防护：ProjectPathGuard、敏感文件拦截、1MB/二进制限制、生成目录跳过、只允许覆盖已有文件。
- 验证：网页端绑定仓库后实测树/查看/编辑保存/diff；保存后文件内容与 diff 均更新；并发写返回 409；测试项目、会话与临时文件已清理。
- 详见 `docs/design/2026-10-28-project-code-workspace.md`。

### 桌面端同步（代码工作区）

- 重新打包并覆盖安装 `/Applications/Aster.app`；`app.asar` 已包含 `CodeWorkspace`。
- 实机验证：无登录落在 `/workbench`；桌面后端 `code/tree`、`code/diff` 对未绑定会话返回 400「当前会话未绑定工作区」，说明路由已生效。
- 桌面端需要先通过「添加工作区 → 选择本机目录」授权并绑定项目，代码入口才会出现。
