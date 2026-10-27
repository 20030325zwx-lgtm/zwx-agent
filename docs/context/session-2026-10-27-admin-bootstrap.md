# 会话交接：默认管理员与关闭注册（2026-10-27）

> **已被 `session-2026-10-27-host-project-workspace-plan.md` 取代**；本份保留为认证改动记录。

## 改动内容

- `AdminUserInitializer` 改为只按管理员用户名判断：账号不存在即创建，不再因用户表已有普通用户而跳过。
- 管理员密码仍优先读取 `app.security.admin-password`；未配置时仅本地开发回退 `admin123`，已有 admin 不重置密码。
- 新增 `app.security.registration-enabled`，默认 false；关闭时 `/auth/register` 返回 403。
- 登录页默认填充管理员凭据，注册按钮保留但禁用置灰；具体部署凭据来自 Git 忽略的 `frontend/.env.local`，未写入受版本控制文件。
- README 与部署配置模板同步初始化和注册开关语义。

## 验证结果

- `mvn -Dtest=AdminUserInitializerTest,AuthControllerTest test`：3 项通过。
- `npm run build`：通过，只有既有大 chunk 警告。
- `mvn -DskipTests package`：通过，生成 `target/aster-agent-0.0.1-SNAPSHOT.jar`。
- 本地数据库 V17 Flyway 历史校验和已同步到当前工作区迁移文件，未修改迁移文件；恢复正常校验启动后 27 个迁移全部通过。
- 本地 `admin` 密码哈希已更新为部署凭据；`POST /auth/login` 返回 200，`POST /auth/register` 返回 403，后端健康检查返回 200。

## 遗留事项

- 部署密码不会明文展示在页面上，但自动填充意味着它仍会进入浏览器静态资源和密码输入框；公网环境应改为用户首次登录后强制改密的模式。
- 上一份摘要 `session-2026-10-27-p0-p2-hardening.md` 的遗留事项继续有效。

## 环境注意事项

- `frontend/.env.local` 已被 Git 忽略，换构建机时需重新配置 `VITE_DEFAULT_ADMIN_USERNAME` 与 `VITE_DEFAULT_ADMIN_PASSWORD`。
- 后端部署环境继续通过外部 `globe.conf` 设置 `app.security.admin-password`，两处密码必须保持一致。
