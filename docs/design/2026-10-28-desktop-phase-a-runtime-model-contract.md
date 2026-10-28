# 阶段 A：桌面运行时验证结论与模型调用契约

日期：2026-10-28。状态：阶段 A 技术验证已完成实测；模型契约为设计稿，实施在阶段 C。
上一份计划：[网页与桌面改造计划](./2026-10-28-web-desktop-workspace-plan.md)、[AI 工作台修订](./2026-10-28-ai-workbench-references.md)。

## 1. 验证环境与方法

- 机器：macOS arm64（darwin 25.6.0），Apple clang 17，JDK 21（Microsoft OpenJDK 21.0.11）。
- 方法：下载可再分发二进制 → 组装捆绑目录 → 源码编译 pgvector 扩展 → initdb/启动 → jlink JRE 启动后端 fat JAR → 空库冷启动到健康检查。
- 所有验证均未触碰 `application-local.yml` 与运行中的开发服务（8123）；测试后端用 8126/8137 端口，测试库端口 5433，测试后已全部停止。

## 2. PostgreSQL 二进制验证（通过）

| 项 | 结果 |
| --- | --- |
| 来源 | Zonky `embedded-postgres-binaries-darwin-arm64v8` 18.6.0（Maven Central，SHA1 校验一致；国内可用阿里云镜像 `maven.aliyun.com/repository/central` 加速） |
| 备选 | theseus-rs `postgresql-binaries` 18.6.1（88.8MB，含 SBOM/provenance，但 GitHub 直连在本网络环境极慢且同样**不含 pgvector**） |
| 可运行性 | `postgres --version`/`initdb`/`pg_ctl` 正常；initdb→启动 5433 端口成功；新集群 39MB |
| 自包含 | bin(19MB)+lib(300MB)+share(5.2MB) 全部齐备，无需系统安装任何依赖 |
| 精简 | 37 个 dylib 全是独立拷贝（`libicudata` 61MB×3），完全重复文件去重可省 178MB，捆绑包可从 392MB 压到约 214MB |

**三个非显而易见的事实（接入时必查）**：

1. 服务端 sharedir 是 Debian 风格 `share/postgresql/`（不是 `share/`），扩展控制文件必须放 `share/postgresql/extension/`。
2. 该构建的 DLSUFFIX 是 `.dylib`（`lib/postgresql/*.dylib`），扩展产物也须用 `.dylib` 命名。
3. Zonky 精简包只有 `initdb/pg_ctl/postgres` 三个二进制：无 `psql`、无 `pg_config`、无服务端头文件。桌面版做管理操作要走 JDBC（驱动已在后端依赖里），不要假设命令行客户端存在。

## 3. pgvector 扩展验证（通过）

- 版本：pgvector 0.8.7 源码（GitHub tarball，130KB）编译，产物 `vector.dylib`（Mach-O bundle arm64，`-bundle_loader` 指向捆绑 postgres）。
- 版本对齐约束：**扩展编译所用源码树必须与二进制同主版本**（18.6 对 18.6）。源码从 `mirrors.aliyun.com/postgresql` 获取（快）。
- 编译方法（可直接脚本化进 `desktop/scripts/prepare-pgvector.sh`）：
  1. 源码树 `./configure --without-readline --without-zlib --without-icu --without-openssl --without-lz4 --without-zstd --disable-nls`（只为准入 PGXS 与生成头）。
  2. 手动生成三个缺失的派生头：`utils/errcodes.h`（`generate-errcodes.pl`）、`nodes/nodetags.h`（`make -C src/backend/nodes node-support-stamp`）、`catalog/*_d.h`（`make -C src/include/catalog`）。
  3. 写仿造 `pg_config`（--pgxs 指向**源码树内** `src/makefiles/Makefile.pgxs` 包装，pgxs.mk 会从该文件路径反推树根）。
  4. `make PG_CONFIG=<fake> PG_CPPFLAGS="-I<源码树>/src/include"`。
  5. 安装：`vector.dylib` → `lib/postgresql/`；`vector.control`+`sql/vector--0.8.7.sql` → `share/postgresql/extension/`。
- 验证结果：`CREATE EXTENSION vector` 成功，`'[1,2,3]'::vector` 读写正常，`pg_extension` 记录 `vector 0.8.7`。

## 4. 内置 JRE 验证（通过）

- `jlink --add-modules java.se,jdk.crypto.ec,jdk.unsupported --compress=zip-6` 从本机 JDK 21 产出 **59MB** 运行时，耗时约 3 秒（`desktop/scripts/prepare-runtime.sh` 已有同款逻辑）。
- 用该 JRE 启动 122MB fat JAR 成功，无缺模块告警。

## 5. 空库冷启动验证（通过）

用 jlink JRE + `--spring.profiles.active=prod` + 显式 datasource 参数（不读任何本地配置文件）启动：

- **4.566 秒**启动完成（进程总耗时 6.09s），`/api/health` 返回 `ok`。
- Flyway V1 baseline、V2 permission modes、V3 model io trajectory 全部 success，共 26 张表。
- `agent_knowledge_vector`（pgvector HNSW/1536 维）自动建表成功。
- RSS 峰值约 297MB（启动后 70 秒时采样）。
- 未配置任何模型 Key、OSS、MinIO、Docling、搜索、MCP，启动不受阻——证实可选依赖在启动期已解耦。

## 6. 启动依赖清单（冷启动实证 + 源码核对）

| 依赖 | 启动期行为 | 结论 |
| --- | --- | --- |
| PostgreSQL + Flyway | 缺失即启动失败 | **硬依赖**，桌面版必须捆绑（本文件第 2 节即方案） |
| pgvector | `AgentKnowledgeVectorStoreConfig` 无条件硬 bean，启动即建表 | 硬依赖；**建议改造**：加 `@ConditionalOnProperty` 使知识库可选，禁用后只关闭知识功能（阶段 D） |
| DashScope SDK | 占位 key 即可创建 bean，调用时才报错 | 启动不阻断；阶段 C 模型重构后由供应商配置取代 |
| OSS（阿里云） | `OssClientProvider` 懒加载，用时才抛异常 | 不阻断；桌面版换本机存储适配器 |
| MinIO | 无 Java 代码引用，仅遗留配置 key | 无影响，可清理 |
| Tika / Docling / MinerU | 空配置即降级，启动不连接 | 不阻断 |
| 联网搜索 | 空 key 返回 `SEARCH_UNAVAILABLE` | 不阻断 |
| MCP | 启动不连任何服务，运行时按租户建连 | 不阻断 |
| JEV 意图路由 | provider=none 时恒空路由器 | 不阻断 |

结论：**桌面"装完即可启动到未配置模型的工作区"只差两件事——捆绑数据库（已验证）与模型配置流程（阶段 C）**。

## 7. 模型调用契约（设计稿，阶段 C 实施）

### 7.1 逻辑对象

```
Provider   供应商：名称、协议(openai-compatible|dashscope)、baseUrl、apiKey(密文)、启用、最近测试结果
Model      模型：providerId、modelId、显示名、能力声明(tools|vision|embedding)、启用
ModelBinding 绑定：scope(USER|SYSTEM)、ownerId、purpose(chat|vision|embedding)、providerId+modelId、版本号
```

- 表草案（实施时定名）：`model_provider`、`model_provider_model`、`model_binding`；旧 `app_configuration` 的 chat/vision 键由迁移 V4 转换为 Provider+Model+Binding，**保留原行归档**，不清库。
- 密钥沿用 `ConfigSecretCipher`（AES-256-GCM），迁移前备份。

### 7.2 解析与调用规则

1. 解析优先级沿用：用户绑定 > 系统绑定 > 环境变量兜底（迁移期兼容）。
2. 会话/运行开始时冻结 `modelConfigVersion`（绑定版本号），执行中全局改动不漂移；跨重启恢复用冻结版本重建客户端。
3. 调用方统一走 `DynamicChatModelFactory`（扩展为按 Provider+Model 构建，缓存键=版本+模型），请求入口、planner/worker/verifier/aggregator、视觉、轨迹全部收口。
4. **已知技术债**：`ManusGraphOrchestrator`/`PlannerNode`/`WorkersNode`/`VerifierNode`/`AggregatorNode` 目前直接注入 Spring AI 自动装配的 `ChatModel` bean（DashScope 默认），**绕过了用户解析**——仅 `ManusAgentController.chatModelFor(userId)` 走了用户配置。阶段 C 必须把图节点改为显式传入已解析模型，否则"任务中选择模型"不生效。
5. 测试探测（`ModelConfigurationProbe`）支持"未保存草稿"测试；失败展示分类原因（鉴权/地址/超时/模型不存在/不支持工具调用）。
6. 协议与品牌分离：OpenAI 兼容 ≠ 原生 Responses；baseUrl `/v1` 拼接规则沿用 `DynamicChatModelFactory` 现逻辑并补测试。

## 8. 体积投影与剩余工作

- 原始载荷：PG 捆绑(去重后 ~214MB) + JRE(59MB) + JAR(122MB) ≈ **395MB**；DMG 压缩后预计 160–190MB（未实测压缩率）。
- 阶段 A 剩余待办（进入阶段 D 前完成）：
  1. Electron `main.cjs` 集成托管 PG：数据目录初始化、独立端口、启动/健康/退出编排；
  2. `desktop profile`（独立 `application-desktop.yml`，禁用 springdoc 同 prod，文档解析 provider 默认 none）；
  3. 回环认证（主进程随机凭据 + 后端校验）——当前 main.cjs **无任何本地鉴权**；
  4. `desktop/scripts/prepare-backend.sh` 用的是 `./mvnw`（本 checkout 不可执行），需改为 `mvn`；
  5. 签名/公证账号与升级备份策略（发布阶段 E）。
