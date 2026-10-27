# Aster 主机项目工作区与共建设计

> 日期：2026-10-27
>
> 状态：调研与实施规划，尚未实现
> 目标：让部署在某台机器上的 Aster 可以在受控范围内登记该机器可见的文件夹，把任务绑定到项目目录，并逐步具备代码读取、修改、测试、Git 隔离和多人共建能力。

## 1. 结论

这不是给现有 `WorkspaceService` 增加一个绝对路径参数，而是新增“主机项目”这一层资源模型。

Aster 需要明确区分四个概念：

| 概念 | 生命周期 | 用途 |
| --- | --- | --- |
| Project（项目） | 长期 | 用户登记的机器目录，例如一个 Git 仓库或普通文件夹 |
| Conversation（任务） | 长期 | 归属项目的聊天与执行历史 |
| Checkout（执行目录） | 单任务或单 run | 直接项目目录、只读目录或 Git worktree |
| Artifact Workspace（产物区） | 会话级 | 当前 `temp/workspaces/{tenant}/{agent}/{conversation}`，存 PDF、下载文件等产物 |

推荐产品模型：**项目是任务的上级容器，任务默认继承项目目录；并行写任务使用 worktree，产物继续放独立产物区。**

## 2. 调研摘要

### 2.1 已确认的共性

1. **目录是会话的一等上下文，而不是某次工具调用的字符串参数。**
   - 当前 Codex 桌面端可观察到项目记录包含 `projectId / label / path / hostId / isGitRepository`，任务创建时选择项目，并区分直接工作目录与 worktree 环境。
   - Claude Code 从当前目录启动会话，支持 `--add-dir` 增加额外可读写目录；目录访问与权限规则绑定。
2. **目录授权与配置发现分离。** Claude Code 的额外目录授予文件访问，但不会自动把该目录全部当成主项目配置来源。
3. **直接编辑和隔离编辑是两种不同模式。** Claude Code/Codex 一类产品用 Git worktree 支持并行任务，防止多个任务争用同一 checkout。
4. **容器部署只能访问显式挂载的目录。** OpenHands 使用 `--mount-cwd` 或 `host_path:container_path:mode`；读写挂载意味着 Agent 可以修改宿主机内容。
5. **仓库上下文不等于把全部文件塞给模型。** Aider 在本地 Git 仓库运行，但只把选定文件与 repo map 放入上下文。

### 2.2 参考资料

- [Claude Code CLI reference](https://code.claude.com/docs/en/cli-reference)：当前目录、`--add-dir`、权限模式。
- [Claude Code permissions](https://code.claude.com/docs/en/permissions)：工作目录、额外目录、workspace trust。
- [Claude Code worktrees](https://code.claude.com/docs/en/worktrees)：任务隔离、恢复、清理与 Git 防越界检查。
- [Claude Code settings](https://code.claude.com/docs/en/settings)：用户、项目共享、项目本地与组织策略作用域。
- [OpenHands Docker Sandbox](https://docs.openhands.dev/openhands/usage/runtimes/docker)：工作目录挂载和读写风险。
- [Aider Usage](https://aider.chat/docs/usage.html)：本地仓库、显式文件上下文与 repo map。

OpenAI Docs 与 Codex manual 在本次调研中持续返回 HTTP 403，因此 Codex 部分只采用当前应用实际可见的项目字段、worktree 调用能力和用户截图，不声称覆盖官方完整契约。

## 3. Aster 当前差距

当前 `WorkspaceService` 已解决“会话产物隔离”，但没有解决“在真实项目中工作”：

- 根目录由 `app.workspace.root` 全局指定，下面自动创建 `{tenant}/{agent}/{conversation}`，用户不能选择已有文件夹。
- `FileOperationTool` 实际只读写会话目录下的 `file/`；无法浏览项目树、搜索代码或编辑项目原文件。
- `TerminalOperationTool` 在会话产物目录运行，并且是只读白名单命令，不具备构建、测试、Git 状态等 harness 基础能力。
- 会话表没有 `project_id`，任务无法按项目分组或恢复到原目录。
- Docker 后端只挂载 `./temp:/app/temp`，看不到宿主机项目目录。
- 多任务若直接写同一目录，没有单写者锁、worktree 或冲突检测。

因此现有工作区应重新命名理解为 **ArtifactWorkspace**。首期不强行重命名类，新增 `ProjectWorkspaceService`，避免大范围回归。

## 4. 产品模型

### 4.1 左侧导航

```text
项目
├─ yu-ai-agent
│  ├─ 新建任务
│  ├─ 默认管理员与登录调整
│  └─ 主机工作区规划
├─ aster-infra
│  └─ 沙箱接入
└─ 未绑定项目
   └─ 历史任务
```

- 顶部“添加项目”只登记目录，不复制、不移动、不删除原文件。
- 新任务默认使用当前项目。
- 项目卡显示机器、路径别名、Git 分支、脏状态、读写模式和在线状态。
- 删除项目只解除 Aster 登记；绝不删除磁盘目录。
- 历史会话 `project_id IS NULL`，继续使用现有产物工作区。

### 4.2 执行模式

| 模式 | 适用场景 | 行为 |
| --- | --- | --- |
| `READ_ONLY` | 了解项目、评审、规划 | 可列目录、搜索、读取、运行无副作用检查，不写文件 |
| `DIRECT` | 单人、串行的小改动 | 直接在登记目录工作，同一项目同一时刻只允许一个写 run |
| `WORKTREE` | Git 项目并行任务 | 为任务创建受管 worktree，写入与主 checkout 隔离 |

MVP 默认 `READ_ONLY`；用户显式切到 `DIRECT` 或 `WORKTREE` 后才开放写能力。Git 项目推荐 `WORKTREE`，普通文件夹只能 `READ_ONLY` 或 `DIRECT`。

## 5. 部署与机器模型

### 5.1 原生部署

后端可以访问当前操作系统用户有权限的目录，但仍只能浏览管理员配置的允许根：

```yaml
app:
  project-workspace:
    roots:
      - id: projects
        runtime-path: /Users/example/Projects
        display-name: Projects
        writable: true
```

### 5.2 Docker 部署

浏览器不能让已经运行的容器凭空访问新的宿主机目录。宿主机根目录必须在部署时挂载：

```yaml
services:
  backend:
    volumes:
      - ${ASTER_PROJECTS_HOST_ROOT}:/workspace-roots/projects:rw
```

应用只保存 `root_id + relative_path`，不把宿主机绝对路径当作执行路径：

```text
root_id=projects
relative_path=team/yu-ai-agent
runtime_path=/workspace-roots/projects/team/yu-ai-agent
display_path=/srv/projects/team/yu-ai-agent
```

这样同一数据库在不同机器或容器路径下不会因绝对路径变化立即失效。

### 5.3 多机器演进

第一阶段一个 Aster 实例就是一个执行节点。数据模型预留稳定 `node_id`，但不能复用当前随机、重启即变化的 `ApplicationInstanceRegistry.instanceId`。

后续多机器模式增加常驻 `aster-runner`：

- runner 用固定 node id 注册 hostname、OS、能力和允许根；
- 控制面只下发 project id、run id 和相对路径；
- 文件与命令在所属 runner 执行；
- node 离线时项目显示 `OFFLINE`，任务不可执行但历史可读。

## 6. 数据模型

### 6.1 `agent_project`

```text
id                  VARCHAR(36) PK
tenant_id           VARCHAR(64) NOT NULL
owner_user_id       VARCHAR(64) NOT NULL
node_id             VARCHAR(64) NOT NULL
root_id             VARCHAR(64) NOT NULL
relative_path       TEXT NOT NULL
canonical_path_hash VARCHAR(64) NOT NULL
name                VARCHAR(120) NOT NULL
project_type        VARCHAR(16)  -- GIT / DIRECTORY
default_mode        VARCHAR(16)  -- READ_ONLY / DIRECT / WORKTREE
access_mode         VARCHAR(16)  -- READ_ONLY / READ_WRITE
trusted             BOOLEAN
status              VARCHAR(16)  -- ACTIVE / MISSING / OFFLINE
git_metadata        JSONB
created_at / updated_at
UNIQUE (tenant_id, node_id, canonical_path_hash)
```

数据库保存 root id 与相对路径用于解析；canonical hash 用于去重，不把真实路径暴露给普通接口。

### 6.2 会话与 run

- `agent_conversation ADD project_id`，允许 NULL 兼容历史数据。
- 建议新增 `agent_run_workspace`：记录 `run_id / project_id / mode / checkout_path / base_ref / branch / head_before / head_after / cleanup_status`。
- run 开始后把解析结果快照到 `agent_run_workspace`，项目配置后来变化不会让恢复任务落到另一目录。

多人共建第二阶段增加 `agent_project_member(project_id, user_id, role)`；MVP 可先沿用 tenant + owner，管理员可见全部项目。

## 7. 后端边界

### 7.1 服务职责

- `ProjectRootRegistry`：读取部署配置中的允许根。
- `ProjectPathGuard`：canonical/realpath 校验、符号链接逃逸拒绝、权限检查。
- `ProjectService`：项目登记、重命名、解除登记、Git 探测、在线状态。
- `ProjectBrowserService`：仅列允许根内部的直接子目录，返回相对路径或 opaque token。
- `RunWorkspaceResolver`：根据项目和执行模式得到 checkout root 与 artifact root。
- `GitWorktreeService`：创建、锁定、恢复、归档、清理受管 worktree。
- `ProjectExecutionLock`：DIRECT 模式单写者租约；复用现有数据库租约思路，不使用 JVM 本地锁。

### 7.2 API 草案

```text
GET    /ai/projects
POST   /ai/projects
GET    /ai/projects/{id}
PATCH  /ai/projects/{id}
DELETE /ai/projects/{id}                 # 只解除登记
GET    /ai/project-roots
GET    /ai/project-roots/{rootId}/children?path=
POST   /ai/projects/{id}/validate
GET    /ai/projects/{id}/git-status
POST   /ai/manus/conversations           # 新增 projectId、executionMode
```

目录浏览接口不接受任意绝对路径；客户端只能提交 root id 与由上一次响应获得的相对路径。

### 7.3 工具装配

引入不可变 `RunWorkspaceContext`：

```text
projectRoot    登记项目的真实根
checkoutRoot   本 run 实际读写根
artifactRoot   现有会话产物根
mode           READ_ONLY / DIRECT / WORKTREE
projectId / nodeId / runId
```

工具分流：

- 代码读取、搜索、编辑、Git、构建测试：以 `checkoutRoot` 为根。
- PDF、联网下载、临时转换文件：仍以 `artifactRoot` 为根。
- 所有返回给模型的路径使用项目相对路径，不泄露机器绝对路径。
- `ToolFactory` 接收 `RunWorkspaceContext`，不再自行猜测工作目录。

## 8. 安全约束

1. **允许根是部署策略，不是用户输入。** 普通用户不能登记 `/`、home、`/etc`、Docker socket 等任意路径。
2. 根与候选目录都先 `toRealPath()`；每一级拒绝符号链接越界，不能只做字符串 `startsWith`。
3. Docker 只挂载需要共建的根，默认 `ro`；明确需要编辑才改 `rw`。
4. 首次登记目录需要管理员确认信任。项目内配置、脚本、`AGENTS.md` 均按不可信仓库内容处理。
5. 默认拒绝读取 `.env*`、私钥、云凭据目录和密码库；例外必须走项目级显式授权。
6. 写文件、终端、Git push、依赖安装继续经过工具确认与审计；项目被信任不等于所有命令自动放行。
7. DIRECT 模式必须有单写者租约；并行写任务强制 worktree 或拒绝。
8. 解除项目、清理 worktree 与删除真实目录是三个动作；前两个不得隐式触发第三个。
9. 文件事件、工具调用、Git head 前后值写入 run 审计，保证可追溯。
10. 目录缺失、挂载丢失或 node 离线时 fail closed，不自动回退到同名其他路径。

## 9. Git 与共建语义

- 项目创建时探测 Git root、当前分支、remote、脏状态；普通文件夹不伪装为 Git 项目。
- WORKTREE 默认从当前 `HEAD` 创建，首期不自动 fetch、不自动 push。
- worktree 路径放在 Aster 管理目录，而不是项目内部会被索引的位置。
- run 结束展示 diff、未跟踪文件、测试结果和提交状态；是否 commit 由用户命令决定。
- 有未提交改动的 worktree不自动删除，标记 `NEEDS_ATTENTION`。
- 共建的核心不是多人同时写主目录，而是多个任务共享项目上下文、通过隔离 checkout 产出可审查改动。

项目级上下文后续可支持：

- 仓库 `AGENTS.md` / `.aster/project.yml`；
- 项目级启用技能、MCP、构建命令与测试命令；
- 项目成员角色 OWNER / EDITOR / VIEWER；
- 项目活动流与任务列表。

## 10. 分批实施

### 批次 A：项目登记与只读浏览（P0）

- V27：`agent_project` + `agent_conversation.project_id`。
- 配置允许根，Docker 增加单一项目根挂载。
- 后端项目 CRUD、目录逐级浏览、canonical path guard、Git 探测。
- 前端增加项目分组、新建项目和新建任务时选择项目。
- 项目仅 READ_ONLY；现有任务保持无项目模式。

验收：不能越过允许根，不能经符号链接逃逸，容器只能看到挂载目录，删除项目不删除磁盘文件。

### 批次 B：项目上下文接入工具（P0）

- `RunWorkspaceContext` 与 `RunWorkspaceResolver`。
- 新增项目树、按路径读取、ripgrep 搜索、Git status/diff 工具。
- Planner/Worker prompt 注入项目名、相对根、Git 状态与模式。
- 产物工具继续写 artifact workspace。

验收：Agent 能理解真实仓库、搜索代码并给出带相对路径的结论，但无法写源码。

### 批次 C：受控直接编辑（P1）

- 项目文件 patch/write 工具，路径守卫统一。
- DIRECT 单写者数据库租约。
- 测试/构建命令使用项目声明的命令模板，禁止任意 shell 起步。
- 前端展示 diff、变更文件、测试结果，危险动作继续确认。

验收：两个任务不能同时直接写同一项目；越界写、敏感文件读写和未授权命令均被拒绝。

### 批次 D：Git worktree 并行（P1）

- `agent_run_workspace` + `GitWorktreeService`。
- 新任务可选 DIRECT / WORKTREE；并行任务默认 WORKTREE。
- 恢复、归档、脏 worktree 保留、定期清扫。
- 子 Worker 首期共享 run worktree，不为每个 Worker 再创建 worktree。

验收：两个任务可并行修改同一仓库且互不污染；重启后能恢复到原 worktree；有改动时绝不静默清理。

### 批次 E：多机器 runner 与团队共建（P2）

- 稳定执行节点、runner 注册/心跳/能力发现。
- 项目成员权限、节点离线状态、远程执行协议。
- 项目级配置、技能与任务活动流。

## 11. 测试矩阵

- 路径：`..`、绝对路径、大小写、Unicode、符号链接、目录被替换后的 TOCTOU。
- 权限：跨 tenant、跨 user、VIEWER 写入、未信任项目、只读挂载。
- 容器：未挂载目录不可见，ro 挂载不能写，host/runtime 路径映射正确。
- Git：非仓库、脏仓库、detached HEAD、submodule、worktree 冲突、进程崩溃恢复。
- 并发：DIRECT 双写竞争、worktree 并行、清理与运行竞态。
- 兼容：旧会话无 project id、现有产物下载、SSE 重连、run resume。
- 审计：每次文件写、命令、确认、Git head 变化可按 run 查询。

## 12. 明确不做

- 首期不允许浏览整台机器或从 Web 前端弹出“服务器原生文件选择器”。
- 首期不做远程 SSH 随意执行；多机器通过受管 runner，而不是保存 SSH 密码。
- 首期不自动 clone、fetch、commit、push。
- 不把宿主机绝对路径发送给模型。
- 不用项目目录替换产物工作区，两者职责保持独立。

## 13. 建议的首个开发切片

先完成批次 A + 批次 B 的只读闭环，不立即开放写：

```text
管理员配置一个允许根
→ Web 端逐级选择文件夹并登记项目
→ 新建任务绑定项目
→ Agent 可列树、搜索、读文件、查看 Git 状态
→ 回答中的路径可点击定位
```

这条链路已经能证明“项目工作空间”价值，同时把宿主机写入、任意命令和并发冲突留到安全模型成熟后再开放。
