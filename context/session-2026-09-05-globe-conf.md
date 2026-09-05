# 会话摘要：2026-09-05 生产环境 globe.conf 外部配置 + 打包部署评估

> 状态：已完成、已构建并重启本地后端（health ok）。管理员密码无需改动（本就内置数据库）。
> 需求：① 管理员密码直接维护在数据库、内置；② 生产环境配置与本地区分，用户在服务器 `/home/globe.conf` 填写配置（API Key 等），程序启动时读取。

## 结论（需求①）

`AdminUserInitializer` 首次启动即把 `admin`/`admin123`（BCrypt）写入 PostgreSQL `users` 表，密码本来就内置在数据库，不依赖任何 env 接线。`APP_SECURITY_ADMIN_PASSWORD` 环境变量仅是可选覆盖，无需进 compose。当前没有改密码 API，改密需直接更新 DB 或后续加接口（遗留项）。

## 改动内容（需求②：globe.conf 机制）

1. **`config/GlobeConfEnvironmentPostProcessor`**（新增）+ `META-INF/spring.factories` 注册：启动时读取外部配置文件（默认 `/home/globe.conf`，properties 格式 UTF-8），解析后 `addFirst` 注入 Environment，**优先级最高**。要点：
   - 路径可覆盖：系统属性 `globe.conf.path` > 环境变量 `GLOBE_CONF_PATH`（compose 侧经容器内固定路径，无需设置）。
   - 缺文件/不可读 → 打日志跳过，绝不阻断启动；值不打印日志。
   - 空值或 `YOUR_*`/`CHANGE_ME_*` 占位符视为未配置，防止模板占位符覆盖真实值。
   - `getOrder()=LOWEST_PRECEDENCE`，保证在 ConfigData（application*.yml）之后执行。
2. **`docker-compose/globe.conf.example`**（新增模板）：DashScope key、JWT secret（必填）+ 搜索/OSS/MinIO/Tika/数据源（可选注释项）。
3. **docker-compose.yml**：backend 挂载 `${APP_GLOBE_CONF:-/home/globe.conf}:/home/globe.conf:ro`。
4. **install.sh**：globe.conf 不存在时从模板自动创建（600 权限）并提示填写后重跑；DashScope 密钥改为 **.env 与 globe.conf 二选一**校验；require_value 改为返回码式。
5. **.env.example**：新增 `APP_GLOBE_CONF`（空=默认 /home/globe.conf）与 DASHSCOPE 二选一说明。
6. **scripts/package.sh**：release 包加入 globe.conf.example。
7. **application-prod.yml**：头部注释说明 prod profile 与 globe.conf 的关系（密钥不在此文件维护）。
8. **README**：部署章节新增「服务器外部配置 globe.conf」小节，安装命令注释同步；另新增「标准发布流程（速查）」八步端到端清单（定版本→自检→打包→传输→安装→验证→升级→回滚含备份命令），注明 pom 版本不要动（与 Dockerfile JAR_FILE/prepare-backend.sh 联动）。
9. **架构文档归位**：README「整体架构」整节迁出，原位置只留指向 `design/agent-architecture.md` 的链接；design 文档的「系统总览」图替换为 README 迁来的当前版（图状态机 + JWT/审计 + OSS/沙箱），第 5 节 ReAct 执行链补充「旧实现保留可回滚、当前为 plans/06 图编排」状态注记。
10. **桌面端重打包**：`zwx-agent-desktop` 版本 0.1.1→0.1.2，`npm run dist:mac` 全流程成功（build:web→assets→backend→runtime→icon→electron-builder，未签名）。已验证新包含当天全部改动：app/assets 含 SwiftUI `--sk-*` 令牌，server/zwx-agent.jar 含 `GlobeConfEnvironmentPostProcessor` 与 `graph/ManusGraphOrchestrator`。产物：`release/ZWX Agent-0.1.2-arm64.dmg/.zip`（各约 255MB）；旧 0.1.1 产物保留作回滚。

## 验证结果

- 验证方式：先用临时单测（已按用户要求删除、不留项目中）验证解析/优先级/占位符/缺文件四类行为，随后移除。
- `mvn -q -DskipTests package`：成功，target jar 已更新（18:19）。
- `launchctl kickstart -k` 重启后端：health `ok`；启动日志出现 `[globe-conf] 未找到 /home/globe.conf，跳过外部配置注入`，证明 spring.factories 注册在真实启动中生效。
- `sh -n` 校验 install.sh / package.sh / stop.sh 语法通过。
- 未提交 git（沿用仓库惯例等用户指示）。

## 优先级顺序（部署时排查用）

globe.conf > 环境变量（compose/.env）> application*.yml > 代码默认值。

## 遗留事项

- 无修改密码接口：改管理员密码目前只能改 DB（bcrypt）或用可选 env 重建；建议后续加 `POST /auth/password` + 前端入口。
- compose 未含 MinIO 服务，纯 compose 部署图片上传仍需 OSS（globe.conf 已支持 `app.storage.*`，若后续加 MinIO 服务即可用）。
- 后端容器无 HEALTHCHECK、无 CI（.github 为空）——上次评估的第 3、4 项缺口仍未做。
- macOS 本地 `/home` 只读，本地开发不受影响（文件必然不存在 → 跳过），`application-local.yml` 未触碰。
