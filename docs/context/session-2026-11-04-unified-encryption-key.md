# 统一本地与服务端配置加密口令（2026-11-04）

## 背景

本地与服务端共享同一个 PostgreSQL，但 `app.config.encryption-key` 不一致：

- 本地使用默认 key（`application-local.yml` 未覆盖）；
- 服务器 `/home/globe.conf` 使用另一把 key。

共享库里的供应商密钥由本地 key 加密，服务器无法解密。0.5.2 的 `603fb8d` 只做了
"绑定密钥不可用时回退下一层"的兜底，没有解决根因。

## 本次改动

1. 生成一把新的 32 字节随机 key，作为两端唯一来源。
2. 停两端后端，备份数据库（`/opt/aster/backups/backup-2026-11-04-1700.sql`）。
3. 用旧本地 key 解密、新 key 重加密全部密文：
   - `model_provider.secret_ciphertext`：6 行全部迁移；
   - `app_configuration.secret_ciphertext`：0 行；
   - `mcp_server_configuration.secret_ciphertext`：0 行；
   - 重加密后 6/6 校验通过。
4. 同步配置：
   - 本地：`src/main/resources/application-local.yml` 新增 `app.config.encryption-key`
     （该文件 Git 忽略，其他行未改动）；
   - 服务器：`/home/globe.conf` 的 `app.config.encryption-key` 替换为同一值。
5. 重启两端后端。

## 验证结果

- 新 key 文件、本地配置、服务器配置三处 SHA-256 一致。
- 本地 `GET /api/ai/creative/skills`：7 项，优化师 `READY`，无回退告警。
- 服务器 `GET /api/ai/creative/skills`：7 项，优化师 `READY`；
  后端日志近 5 分钟无「模型绑定缺少可解密密钥，回退下一层」。
- 两端后端 health 均为 `ok`。
- 6 条供应商密钥全部可用新 key 解密，金丝雀哈希与库中 hint 一致。

## 回滚

- 数据库：`/opt/aster/backups/backup-2026-11-04-1700.sql`。
- 配置：
  - 本地旧配置备份在迁移期间临时保存，已清理；
  - 服务器 `/home/globe.conf.bak-2026-11-04-1700`（root 600）。
- 注意：数据库与配置必须**成对回滚**。只回滚配置或只回滚数据库，会导致密文与新口令不匹配。
- 回滚后仍会回到"两端 key 不一致"的旧状态，需要重新执行本迁移才能长期可用。

## 注意事项

- 新 key 只存在于本地 `application-local.yml`（Git 忽略）和服务器 `/home/globe.conf`（600），
  未进入 Git、未写入任何文档或日志。
- 后续更换口令必须按 `docs/DEPLOYMENT.md` §4.2 的顺序：先重加密存量密文，再同步两端配置。
- 本地与服务端共享数据库时，任何一端在"模型设置"里保存密钥都会用本端口令加密；
  两端口令必须始终保持一致。
- 中期建议给本地开发使用独立数据库，彻底消除跨环境密钥耦合。
