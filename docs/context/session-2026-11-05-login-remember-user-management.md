# 登录记忆 + 管理员用户管理（2026-11-05）

## 改动内容

### 1. 登录页（`frontend/src/views/Login.vue`）
- 内置默认管理员用户名 `admin`（仅 DEV 预填；仍优先读 `VITE_DEFAULT_ADMIN_USERNAME`），**密码不再预填**。
- 新增「记住密码」勾选框：
  - 勾选且登录成功后，用户名+密码以 `v1:` 前缀 + Base64 存入 `localStorage['aster.login.remembered']`。
  - 下次打开登录页自动回填用户名与密码并勾选。
  - 取消勾选立即清除本机保存的密码；登录时未勾选也会清除。
  - 仅为本机便利功能，不是加密：同源脚本可读取，页面已提示公共设备勿勾选。
- 后端默认密码 `admin123` 由 `AdminUserInitializer` 既有逻辑保证（账号不存在时创建），本次未改动、未重置现有账号密码。

### 2. 管理员用户管理
后端（`AuthController`、`UserRepository`）：
- 新增 `GET /auth/users`：管理员列出**本租户**账号（id/username/tenantId/role/status），不返回密码哈希。
- 新增 `DELETE /auth/users/{id}`：管理员删除本租户账号；禁止删除当前登录账号；禁止删除租户内最后一个管理员；跨租户返回 404。只删账号，历史会话/运行数据保留。
- `POST /auth/users` 扩展可选 `role`（USER/ADMIN，缺省 USER），非法角色 400；`AuthRequest` 保留两参构造，登录/注册不受影响。
- `UserView` 增加 `status` 字段。

前端：
- `frontend/src/views/UserManagement.vue`：添加用户（用户名/密码/角色）、账号列表、删除（`window.confirm` 二次确认）；当前账号删除按钮禁用；非管理员显示无权限提示。
- 路由 `/users`（`name: UserManagement`）。
- `GlobalNav.vue` 底部新增「用户管理」入口，**仅 role=ADMIN 可见**；后端接口同样做管理员校验，前端条件只是展示层。

## 验证结果

- `mvn -q -Dtest=AuthControllerTest test`：10 项通过（新增管理员开户、非法角色、列表鉴权、删自己、删最后管理员、跨租户删除等用例）。
- `npm run build`：通过。
- 本地后端已重建 JAR 并 launchctl 重启（健康检查 ok）。
- 接口冒烟：`admin/admin123` 登录成功；`GET /auth/users` 返回 3 个账号；创建 `ui-smoke-user` 后删除成功；删除自己返回 400。
- 浏览器实机验证：登录页只预填 `admin`、密码为空；勾选记住密码登录后重进登录页自动回填；管理员导航出现「用户管理」；页面添加 `ui-ui-test-1` 后列表变 4 条，删除后回到 3 条并提示「已删除」；把本地用户角色改为 USER 后导航不再显示「用户管理」。
- 验证产生的临时账号与浏览器 localStorage 已清理。

## 遗留事项

- **全量 `mvn test` 在本机卡住**：surefire fork 在 Spring `ClassPathScanningCandidateComponentProvider` 扫描类路径时阻塞（CPU≈0），与本次改动无关，属环境问题；定向测试与编译通过。后续如需全量回归，考虑先排查类路径上的慢速挂载/异常 jar。
- 用户删除为硬删除，不联动清理该账号的会话、记忆、运行记录（页面对此有说明）。若需要级联清理或改为停用（status=DISABLED），另开批次。
- 未部署：服务器仍为 0.7.0，本次前后端改动均未发布。

## 环境注意事项

- 本地后端仍连生产库（SSH 隧道 15432）：本次新增接口只做了读写账号表的冒烟，临时账号已删除。
- `application-local.yml` 未读取、未修改。
- 可用于本地验证的管理员凭据为 `admin/admin123`（用户确认）。
