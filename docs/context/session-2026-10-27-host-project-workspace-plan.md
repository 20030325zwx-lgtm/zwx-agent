# 会话交接：主机项目工作区规划（2026-10-27）

## 本次完成

- 认证与登录页改动已提交：`9c8dc08`（初始化管理员并关闭公开注册）；用户已有 V17 修改与 `release/` 未纳入提交。
- 调研当前 Codex 应用项目/host/worktree 模型、Claude Code 工作目录与 worktree、OpenHands 容器挂载、Aider 仓库上下文。
- 新增 `docs/design/2026-10-27-host-project-workspace.md`，规划项目、任务、执行 checkout、产物区四层模型。
- 方案覆盖原生部署、Docker 映射、多机器 runner 演进、数据库/API、工具装配、安全边界、Git 隔离、前端项目分组和五批实施路线。

## 核心决策

- 现有 `WorkspaceService` 是会话产物区，不直接改造成任意宿主机目录；新增 `ProjectWorkspaceService` 与 `RunWorkspaceContext`。
- 部署管理员先配置允许根，用户只在允许根内逐级选择；Web API 不接受任意绝对路径。
- Docker 必须在启动时显式挂载宿主机目录，数据库保存 `root_id + relative_path`，避免容器内外绝对路径混用。
- MVP 先交付项目登记和只读代码理解；直接写入与 Git worktree 随后分批开放。

## 验证与限制

- 设计文档已通过 `git diff --check`。
- OpenAI Docs 与 Codex manual 本次访问均返回 HTTP 403，因此 Codex 部分仅依据当前应用实际可见字段、可调用 worktree 能力和用户截图，不虚构官方契约。
- 本轮只做设计，不创建 V27、不修改运行代码或 Flyway 文件。

## 下一步

按设计文档批次 A 开始：先确定生产服务器允许挂载的宿主机根目录，再实现 V27、项目 CRUD、目录浏览与只读项目导航。
