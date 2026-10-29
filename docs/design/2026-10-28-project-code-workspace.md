# 项目代码工作区（文件浏览 / 编辑 / 改动）

日期：2026-10-28
范围：`ProjectCodeService`、`ProjectCodeController`、`CodeWorkspace.vue`、`SuperAgent`

## 目标

让绑定工作区的会话拥有接近代码助手的可见性：能看项目文件、直接改代码、看到智能体本次运行的改动。
后端代码工具（读文件 / ripgrep / patch / 任务 / commit）此前已经具备，本次补齐「看得见、改得动」的界面与只读/读写 API。

## 目标目录如何选择

| 会话状态 | 代码工作区目标 | 界面上显示 |
| --- | --- | --- |
| 选中了一次运行（runId 存在且该 run 有 checkout） | 该 run 的 checkout（Git 项目为 worktree） | 运行工作区 · 分支名 |
| 没有 run，或 run 没有 checkout | 绑定项目的根目录 | 项目目录 |

这样在智能体改完代码后，打开「改动」看到的就是本次运行相对 base 的差异，而不是主工作区的状态。

## API

均挂在 `/ai/manus/conversations/{conversationId}/code` 下，要求会话属于当前用户：

- `GET /tree?path=&runId=`：单层目录列表，目录优先排序；
- `GET /file?path=&runId=`：UTF-8 文本内容 + 大小 + 修改时间；
- `PUT /file?path=&runId=`：保存文本，body 为 `{content, expectedModifiedAt}`；
- `GET /diff?runId=`：改动摘要（状态、增删行数）+ unified diff。

## 安全与边界

- 所有路径经 `ProjectPathGuard`：必须落在项目/checkout 根内，拒绝符号链接逃逸；
- `.git`、`.env*`、私钥/证书等敏感路径不列出、不可读、不可写；
- 文件上限 1MB，二进制（NUL 字节）拒绝在线查看；
- 保存只允许覆盖已存在文件；`expectedModifiedAt` 不匹配返回 **409**，由界面提示刷新；
- 目录列表跳过 `node_modules`、`target`、`dist`、`release`、`runtime` 等生成/运行时目录；
- 桌面端仍受本机目录授权清单约束（绑定项目时已授权）。

## 界面

- 会话绑定工作区后，顶部出现「代码」入口，打开右侧抽屉（宽度 min(1180px, 96vw)）。
- 「文件」页：左侧惰性展开的目录树，右侧带行号与语法高亮的只读视图；点「编辑」切换为等宽文本域，保存后立刻刷新改动。
- 「改动」页：左侧改动文件列表（状态 A/M/D/U、增删行数），右侧逐文件 unified diff，新增/删除行分色。
- 高亮使用 `highlight.js` core + 常用语言（Java/JS/TS/XML/JSON/CSS/SQL/Bash/Python/YAML/Markdown），未知类型按纯文本显示。
- 运行中切换 `runId` 会自动重新加载树与 diff；切换会话时抽屉关闭。

## 已知限制

- 没有 hunk 级「接受/拒绝」：diff 是只读审阅，修改要么在编辑器里直接改，要么交给智能体重新 patch。
- 未跟踪文件不显示 diff（只列在改动列表里），因为没有可对比的 base。
- 没有 LSP、语义索引、终端面板；代码智能仍依赖 ripgrep。
- worktree 改动合回主分支仍需智能体或人工执行 git 操作，界面不提供一键合并。
- 网页端只能编辑服务端工作区目录；编辑本机代码需使用桌面端。

## 后续

1. patch 变更为「待审阅变更」，支持按文件/按 hunk 接受或拒绝；
2. 未跟踪文件生成伪 diff（全量新增）并支持一键纳入版本管理；
3. 执行日志面板（build/test/lint 流式输出）与失败后的一键修复循环；
4. LSP / tree-sitter 大纲与符号搜索。
