# 会话摘要：桌面端产物保存（另存为 + 导出到授权目录）（2026-11-04）

## 背景

桌面端的后端跑在本机，工作区目录已经能通过系统选择器授权；但产物卡片只有浏览器下载（落到默认下载目录），
不能"另存为"到用户挑的位置，也没有"导出到文件夹/在访达中显示"。本轮补齐 A+B。

## 改动内容

### A. 桌面端「另存为」与「在访达中显示」（Electron）
1. `desktop/electron/main.cjs`
   - `desktop:save-file`：渲染进程传入文件字节（先经鉴权 fetch blob），主进程弹 `dialog.showSaveDialog`
     （默认落在下载目录 + 原始文件名），确认后 `fs.promises.writeFile` 落盘，返回绝对路径；用户取消返回 null。
   - `desktop:reveal-file`：`shell.showItemInFolder(path)`。
2. `desktop/electron/preload.cjs`：暴露 `saveFile` / `revealFile`。
3. 前端新增 `frontend/src/api/desktop.js`：`desktopSaveAvailable()`、`saveUrlWithDialog(url, fileName)`、
   `revealInFolder(path)`、`chooseExportDirectory()`；浏览器环境自动判定不可用。
4. `DeliverableCard.vue`：桌面端才显示「另存为…」（行内）与「全部保存到文件夹…」（头部），保存后给
   「已保存：<绝对路径> · 在访达中显示」。

### B. 后端导出到授权目录
1. `WorkspaceFileExportService`：校验桌面模式 + `DesktopDirectoryGrantService.requireGranted(targetDir)`，
   逐个复制工作区文件（优先统一工作区、回退旧工具目录），重名默认加 ` (1)` 后缀（`overwrite=true` 时可覆盖），
   返回 `[{from,to}]`（真实绝对路径）。
2. `POST /ai/manus/files/export`（`ManusAgentController`）：`{conversationId, paths[], targetDir, overwrite}`；
   非桌面模式 400、目录未授权 400、会话不属于当前用户 404。
3. 单测 `WorkspaceFileExportServiceTest` 6/6：正常复制、重名不覆盖、overwrite、未授权拒绝、缺文件/空 paths、非桌面拒绝。

## 验证结果

- backend `mvn -DskipTests compile` 通过；导出服务单测 6/6；前端 `vite build` 通过；Electron 两文件 `node --check` 通过。
- 未做真机验证：Electron 打包应用内点「另存为…」需要构建桌面端并运行（本轮未跑）；后端导出接口需桌面模式
  （`app.desktop.loopback-token` 非空）才能调用，本地 Web 模式返回 400 属预期。

## 遗留事项

- 桌面端 UI 的端到端验证（保存框、取消、错误提示、在访达中显示）留到下次打包桌面端时进行。
- 「全部保存到文件夹」依赖本机后端导出接口；如果桌面端连的是远端后端（remote 模式），该按钮会返回 400，
  后续可按 `runtimeState.backendMode` 隐藏或给出说明。
- P4 候选：模板库 UI（上传/管理 docx/pptx 模板），产物卡片「预览」、批量 zip 打包。

## 环境注意事项

- `window.zwxDesktop` 只在 Electron 里存在；Web 构建下按钮自动隐藏，不影响现有下载流程。
- IPC 传大文件（≤50MB）走结构化克隆，一次性拷贝；更大文件后续应改为分块或"直接给路径"的 IPC。
