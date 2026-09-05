# 会话摘要：2026-09-05 前端 SwiftUI 风格整体改版

> 状态：已完成并部署到本地环境。`npm run build` 通过，vite dev 重启后前端 200，后端 health ok。
> 需求：前端整体视觉重构为 Apple / SwiftUI（macOS 浅色）风格，用户确认「浅色为主 + 全部页面」。
> 追加（同日晚些时候）：首页整体布局重设计——悬浮胶囊导航 + 居中 Hero 大搜索 + 特色大卡 + 三列问答网格，见文末「追加批次」。

## 追加批次：Home.vue 首页布局重设计（Launchpad 式）

需求：首页整体布局重新设计，参考市面上美观的 AI 产品首页（Raycast/Perplexity 式居中大搜索、Linear/Arc 式悬浮导航、Bento 特色卡片）。

改动（仅 `src/views/Home.vue`，script 逻辑不变，仅微调模板+全新样式）：

1. **悬浮胶囊导航**：顶栏改为 sticky 悬浮圆角胶囊（max-width 1080、毛玻璃+白描边+大阴影），Skill/MCP 按钮改圆形 pill；移动端只留图标。
2. **居中 Hero**：`hero-badge`（呼吸点+YOUR AI WORKSPACE）、54px 大标题（「可靠的下一步」用 `background-clip: text` 蓝紫粉渐变字）、副标题、**居中大搜索框**（620px、60px 高、focus 时 ring+上浮、带清除按钮）；下方 meta 胶囊（智能体数量、多步执行、知识库检索）。
3. **背景光斑**：`.bg-glow` 三个 radial-gradient 光斑（蓝/粉/绿），pointer-events:none。
4. **Bento 结构**：「自主智能体」升级为通栏**特色大卡**（featured：72px 大图标、hover 时底部柔光 .featured-glow 显现、图标 hover 微旋转、CTA「开始任务 ›」）；「对话问答」改三列网格（900px 断点两列、720px 单列）。
5. **搜索无结果**：新增居中空状态卡（大 ⌕、清除搜索按钮）；分类小标题增加贯穿 hairline（.heading-rule）。
6. **页脚**：新增一行居中 catalog-footer。

验证：`npm run build` 通过（1.72s）；`launchctl kickstart -k` 重启前端后 200，curl 抽查 vite 已下发新 Home.vue（hero-badge/featured-grid 存在）。后端无改动。


## 改动内容（纯前端，zwx-agent-frontend/）

设计体系（新增 `--sk-*` 设计令牌，保留 `--zwx-primary*` 4 个主题钩子供运行时换肤）：

1. **src/style.css**：全新设计令牌——SF 字体栈（-apple-system/SF Pro/PingFang SC）、`#f2f2f7` 分组背景、Apple 语义色（label/label-2/label-3、separator）、系统色（blue #007aff / green #34c759 / red / orange）、三级圆角与阴影（card/raised/pop）、毛玻璃材质 `--sk-blur: saturate(180%) blur(20px)`、`::selection`、focus ring 统一为 3px 主题色 ring。
2. **src/App.vue**：全局 reset + 细滚动条（透明轨道、半透明滑块、background-clip）；移除未使用的 HelloWorld import。
3. **组件**：
   - `ConversationSidebar.vue`：macOS 材质侧栏（半透明+blur+hairline 右边框）、内嵌分组列表、选中项改主题色实底白字、新对话按钮灰色填充+主题色＋号；移动端抽屉用 Apple 弹性曲线 `cubic-bezier(0.32,0.72,0,1)`。
   - `ChatRoom.vue`：iMessage 风格——用户气泡为 `color-mix` 主题色渐变+白字（右下角 6px），AI 消息无底色；发送按钮改圆形↑（模板把「发送 ↑」文本改为「↑」，保留 aria-label）；建议提示改白卡片；执行过程/标签全部胶囊化；代码块深色 #1d1d26；trace 弹层毛玻璃化。
   - `AgentSettingsMenu.vue`：macOS NSPopover 浮层（blur+shadow-pop+入场动画）；主题色板换成 Apple 系（blue #007aff / emerald #2aa254 / rose #e0315f / violet #8250df）；桌面配置输入改 iOS 填充式。
   - `AiAvatarFallback.vue`：渐变改 Apple 色、圆角 50%→32%（squircle 感）。
   - `AppFooter.vue`：轻量换肤（未路由引用，保持一致性）。
4. **视图**：
   - `Login.vue`：毛玻璃卡片+渐变背景、App 图标式 squircle badge、iOS 填充式输入框。
   - `Home.vue`：sticky 毛玻璃顶栏、tinted 按钮、大标题 hero（44px/-0.025em）、iOS 搜索框（白卡+SVG 放大镜）、卡片 hover 上浮、SF 大圆角渐变图标、顺带修复 `clearFilter` 引用未声明 `activeCategory` 的 ReferenceError。
   - `LoveMaster.vue` / `SuperAgent.vue` / `TravelPlanner.vue` / `TestAgent.vue`：统一 60px 毛玻璃导航条、hairline 分隔、聊天容器白卡大圆角；TravelPlanner 执行面板材质化。
   - `SkillSettings.vue`：iOS 分段控件（agent-tabs）+ Apple 绿开关（44x27，22px 滑块）+ 白卡行。
   - `McpSettings.vue`：同族卡片/开关/图标 squircle，红色删除态。
   - `KnowledgeAdmin.vue`：毛玻璃页头、左侧列表选中态主题色 soft、预览面板白卡圆角、状态徽章（ready 绿/failed 红/indexing 橙）。
5. **TravelPlanner.vue 为整文件重写**（原 style 单行超长被截断），template/script 从 Read 输出逐行复制保留，无逻辑改动。

## 兼容性说明

- 运行时换肤机制未动：AgentSettingsMenu 仍通过 `document.documentElement.style.setProperty('--zwx-primary*')` 换肤，4 个主题钩子在 style.css 中有默认值（蓝）。
- 使用了 `color-mix()`（Chrome 111+/Safari 16.2+），本地 Chrome/Edge/Safari 新版均支持；如需兼容旧浏览器需回退纯色。
- 未使用的遗留文件 `views/TravelPlanner 2.vue`、`components/HelloWorld.vue` 仍在仓库（HelloWorld 已无人引用），其中 TravelPlanner 2.vue 还引用已删除的旧变量，但**未被路由/导入，不影响构建**。

## 验证结果

- `npm run build` 成功（1.42s，全部 chunk 正常产出）。
- `launchctl kickstart -k` 重启前端，`http://127.0.0.1:3000/` 返回 200；`GET /api/health` → ok。
- vite 正常下发新 style.css（curl 抽查确认）。
- 页面视觉人工验收待用户浏览器确认（登录页/首页/聊天页/设置页）。

## 遗留

- 后端无改动、无需重启；前端 dist 已随 build 更新（如走 nginx 部署需另行发布）。
- 可选后续：深色模式（令牌已按语义命名，加 `@media (prefers-color-scheme: dark)` 覆盖 --sk-* 即可）；删除遗留的 TravelPlanner 2.vue / HelloWorld.vue（需用户确认）。
- 本批改动未提交 git（沿用仓库惯例等用户指示）。
