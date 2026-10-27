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
- 原生部署默认根为 `${user.dir}/projects`；Docker 使用 `/app/projects`，宿主机通过 `ASTER_PROJECTS_HOST_ROOT` 挂载。

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
- MVP 只建立可信工作区边界和任务绑定，尚未把 Agent 文件/终端工具的执行目录切换到项目工作区，也未实现项目删除、导入已有目录或 Git worktree。

## 下一步

按设计文档继续接入只读项目导航，再引入按 run 隔离的 Git worktree 和写操作确认；生产部署时显式设置 `ASTER_PROJECTS_HOST_ROOT` 为宿主机允许根。
