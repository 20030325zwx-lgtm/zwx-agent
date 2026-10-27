# 会话交接：主机项目工作区 MVP（2026-10-27）

## 本次完成

- 认证与登录页改动已提交：`9c8dc08`（初始化管理员并关闭公开注册）；用户已有 V17 修改与 `release/` 未纳入提交。
- 调研当前 Codex 应用项目/host/worktree 模型、Claude Code 工作目录与 worktree、OpenHands 容器挂载、Aider 仓库上下文。
- 新增 `docs/design/2026-10-27-host-project-workspace.md`，规划项目、任务、执行 checkout、产物区四层模型。
- 方案覆盖原生部署、Docker 映射、多机器 runner 演进、数据库/API、工具装配、安全边界、Git 隔离、前端项目分组和五批实施路线。
- 新增 V27：`agent_project` 持久化项目元数据，`agent_conversation.project_id` 可空关联项目，旧会话无需迁移即可继续使用。
- 新增 `/api/ai/projects` 列表/创建接口；创建时只接受一级目录名，在配置根真实路径下原子创建目录，拒绝绝对路径、分隔符、`.`、`..`、控制字符、已有目录和符号链接。
- 会话创建支持可选 `projectId`，绑定前校验当前租户和用户所有权；会话摘要返回 `projectId`。
- 超级智能体侧栏改为按工作区分组，支持新建工作区、选择工作区、新建绑定任务，并保留“未绑定工作区”分组。
- 侧栏视觉按开发工具重构为紧凑项目树：中性灰层级、工作区一级节点、任务缩进子项、当前任务红点、相对时间；创建表单改为独立弹窗，并新增本地任务搜索。
- 原生部署默认根为 `${user.dir}/projects`；Docker 使用 `/app/projects`，宿主机通过 `ASTER_PROJECTS_HOST_ROOT` 挂载。
- 新增已有目录选择：本地模式从当前机器目录树选择绝对路径；生产 root 模式只能浏览配置根内的相对路径。两种模式均解析真实路径并拒绝符号链接，V28 持久化路径范围与规范化路径。
- 代码 harness 第一阶段完成：绑定项目的对话会装配 `coder` 只读工具，可列目录树、ripgrep 搜索、读取源码、查看 Git status/diff；路径守卫拒绝绝对路径、穿越、符号链接与常见敏感文件，生成目录默认排除。
- 代码 harness 第二阶段完成：`applyProjectPatch` 经人工确认后执行 `git apply --check` 与受控写入，返回当前 diff；V29 增加项目单写者租约，run 完成/中断时释放并带过期兜底，恢复执行沿用同一 run 身份与幂等工具账本。
- 代码 harness 第三阶段完成：`listProjectTasks` 探测 Maven、Gradle 及根目录/一级子目录 Node build/test/lint，`runProjectTask` 只接受探测出的任务 ID；执行经人工确认和项目写租约，使用参数数组、精简环境、超时与输出上限。
- 代码 harness 第四阶段完成：V30 持久化 run checkout；Git 项目默认从当前 HEAD 创建 `aster/run-*` 独立分支与受管 worktree，恢复时复用，非 Git 目录保持 DIRECT；`commitProjectChanges` 仅在当前 checkout 暂存并本地提交，拒绝敏感/超大文件，关闭 hooks、签名且不提供 push。

## 核心决策

- 现有 `WorkspaceService` 是会话产物区，不直接改造成任意宿主机目录；新增 `ProjectWorkspaceService` 与 `RunWorkspaceContext`。
- 部署管理员先配置允许根，用户只在允许根内逐级选择；Web API 不接受任意绝对路径。
- Docker 必须在启动时显式挂载宿主机目录，数据库保存 `root_id + relative_path`，避免容器内外绝对路径混用。
- MVP 先交付项目登记和只读代码理解；直接写入与 Git worktree 随后分批开放。

## 验证与限制

- 设计文档已通过 `git diff --check`。
- OpenAI Docs 与 Codex manual 本次访问均返回 HTTP 403，因此 Codex 部分仅依据当前应用实际可见字段、可调用 worktree 能力和用户截图，不虚构官方契约。
- 定向测试 `ProjectWorkspaceServiceTest,AgentConversationProjectBindingTest`：4/4 通过；全量 `mvn test`：290 个测试通过、1 个跳过。
- `npm run build`、`mvn -DskipTests package` 通过；本地后端重启健康检查为 `ok`，Flyway 已成功迁移到 V27。
- 实机页面创建“演示工作区”成功，生成 `projects/workspace-demo`，首个会话正确归入工作区；窄视口侧栏已截图检查。
- 本地实机从目录选择器登记 `Desktop/agent/yu-ai-agent` 成功，工作区与首个任务正确出现；本地 54 条未绑定超级智能体会话已按用户要求删除并复核为 0。
- MVP 已建立可信工作区边界、已有目录登记和任务绑定；尚未把 Agent 文件/终端工具的执行目录切换到项目工作区，也未实现项目删除或 Git worktree。

## 下一步

后续补充 worktree 生命周期管理 UI、变更文件/Diff/检查结果专用面板，以及 OS/容器级构建沙箱；生产部署时显式设置 `ASTER_PROJECTS_HOST_ROOT` 为宿主机允许根。
