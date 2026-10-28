# 会话摘要：阶段 C/D/E 交付——模型目录、桌面独立运行与打包（2026-10-29）

> 追补（同日晚些时候，见提交 6d47735/942713a/23ea43f）：工作台常驻外壳与蓝色主题重构、
> 设置中心框架（/settings/{model,skill,mcp,memory,permission}，旧 URL 全重定向）、
> 打包应用首启三连修复并实测通过。最新状态以本追补与 Git log 为准。

接续 `session-2026-10-29-desktop-phase-a-workbench-b1.md`（B1 骨架见该文件；本文件为最新状态）。
用户指令：按顺序完成设计文档各阶段交付，每项完成先提交 Git。

## 已提交（本会话 5 个提交）

1. `3b7ceaa` docs：设计归档 + 阶段 A 验证 + 模型契约
2. `3a69ebd` 功能：工作台 B1 骨架（新首页/导航/双主题令牌）
3. `ddc8af1` 功能：模型供应商目录（阶段 C）
4. `b7b45ec` 功能：桌面独立运行（阶段 D）
5. （待提交）docs：C/D/E 交付文档 + 本摘要

## 改动内容（按阶段）

- **C 模型目录**：V4 迁移（provider/model/binding/revision 四表 + agent_run 冻结列 + 旧配置自动迁移）、ModelCatalogService（CRUD/删除保护/草稿测试/四级解析链）、新 API、ManusAgentController 解析改走目录并冻结模型身份、`/model-catalog` 前端页（antd 显式注册 + `--wb-*` 令牌）。
- **D 桌面独立运行**：JwtAuthFilter 回环令牌通道、application-desktop.yml、main.cjs 重写（托管 PG 生命周期/动态端口/健康门控/日志/诊断页）、前端桌面免登录、prepare-pgserver.sh 捆绑脚本（147MB 产物）。
- **E 打包**：`desktop/release/Aster-0.1.2-arm64.dmg`（337MB，未签名开发包）。

## 验证结果

- C：scratch 库 V1~V4 冷启动 3.5s；API 冒烟（创建/绑定/测试/删除保护）全过；迁移 SQL 实测正确；浏览器全流程验收（截图）。
- D：`desktop/scripts/verify-desktop-chain.sh` 端到端通过（initdb→建库→PG 启动→desktop 后端→令牌 200/401/401）。
- E：dmg/zip 构建成功（无签名，按约定仅开发测试包）。
- 全程未触碰 `application-local.yml` 与开发服务（8123/3000 正常）；测试进程/库已清理。

## 本会话踩坑记录（排查即查即用）

1. npmmirror 上包名是无 scope 的 `ant-design-x-vue`；`@ant-design/x-vue` 404。
2. script setup 下 antd 组件必须显式 import 后用 PascalCase，kebab `a-xxx` 不注册即渲染成无样式元素。
3. `listModelSettings()` 返回 `{models:[...]}` 不是数组。
4. Zonky 版本 `18.6.0` 对应 PG 源码 `18.6`（用 `postgres --version` 自报值对齐）。
5. Zonky 精简包：无 psql/createdb、DLSUFFIX `.dylib`、扩展目录 `share/postgresql/extension`。
6. `postgres --single` 建库必须在 pg_ctl start **之前**（运行中受 postmaster.pid 锁限制）。
7. dylib 去重必须用**相对**符号链接；绝对链接指向临时目录会悬空导致 dyld Abort trap 6。
8. Maven Central 对连续请求 403 限流，国内直接用 `maven.aliyun.com/repository/central` 镜像。
9. Maven 镜像不支持 Range 断点续传（403），失败须整包重下。

## 遗留事项（下一会话候选）

1. 旧页面下线与设置体系迁移：`Home.vue` 已无入口；`ModelSettings/McpSettings/SkillSettings` 等仍挂旧 WorkspaceLayout，需迁入工作台设置框架与 `--wb-*` 令牌。
2. VISION 绑定目前仅目录存储，图片理解业务尚未消费；embedding 仍走 DashScope SDK bean。
3. 干净 arm64 Mac 实装 dmg 首启验收；升级前 pgdata 自动备份；Apple 签名/公证（需开发者账号）。
4. `AgentSettingsMenu.vue` 未适配 runtimeState/令牌机制（远程模式仍可用）。
5. SuperAgent 重组进 Workbench Shell（B 阶段剩余项）。
6. 验证残留：admin 账号下空会话 `b2e9b8e2-7901-4dcd-9039-0688fe89396a` 可删；`docs/context/session-2026-10-28-ai-workbench-handoff 2.md` 是旧副本，可删。

## 环境注意事项

- 开发服务保持 launchctl 常规：后端 8123 + 前端 3000，本轮未重启；**开发后端仍是旧 JAR**（无 V4 迁移），下次重启时 Flyway 会自动应用 V4 并迁移旧模型配置，属预期行为。
- `desktop/pgserver/`、`desktop/server/`、`desktop/runtime/`、`desktop/app/`、`desktop/release/` 均为构建产物，已 gitignore。
- 重跑桌面验证：`sh desktop/scripts/prepare-pgserver.sh && sh desktop/scripts/verify-desktop-chain.sh`。

> 再追补（提交 b608265）：按用户反馈把工作台首页从"问候+对话框"改为**服务概览仪表盘**
> （统计卡：工作区/会话/进行中/已完成/待处理；任务动态分组时间线；工作区列表；服务状态）。
> 后端新增 `/ai/manus/runs/overview`（含会话标题与任务输入，修复运行卡深链缺 conversationId）。
> 任务发起收归任务页；删除 WorkbenchComposer 与 SuperAgent 草稿消费死代码。
> 开发后端已重启并应用 V4 迁移（旧 chat/vision 配置已自动转入供应商目录）。
> 再追补（提交 7a2ab0d）：**工作流垂直试点完成并真实跑通**——原生引擎（V5 workflow_run）
> + 首个工作流「商品海报生成」（MCP 搜图/Commons 回退/LLM 译英 → Java2D 海报 → OSS 产物），
> 前端新增「工作流」导航页；详见 docs/design/2026-10-29-workflow-pilot-product-poster.md。
> 再追补（提交 f21430a）：**桌面本机目录授权完成**——系统目录选择器 → 主进程持久化授权清单
> → 回环令牌推送后端 → select/browse 以清单为边界；授权链路脚本验证 + 打包应用实测通过。
> 详见 docs/design/2026-10-29-desktop-file-authorization.md。
