# 阶段 C/D/E 交付：模型目录、桌面独立运行与打包

日期：2026-10-29。状态：C/D 已实现并实测，E 产出未签名开发包（签名待 Apple 开发者账号）。
承接：[阶段 A 验证与模型契约](./2026-10-28-desktop-phase-a-runtime-model-contract.md)。

## 阶段 C：模型管理闭环（已交付）

### 后端

- **V4 迁移**（`V4__model_provider_catalog.sql`）：`model_provider`（连接与密钥，系统级 owner=''）、`model_provider_model`（模型条目+能力声明 JSONB）、`model_binding`（scope/owner/purpose 唯一）、`model_catalog_state`（修订号）；`agent_run` 增加冻结列 `model_revision/model_provider/model_name`。旧 `app_configuration` 的 chat/vision 行迁移为供应商+模型+绑定（按 owner+protocol+baseUrl+密文去重，密文原样转移，SQL 已实测）。
- **ModelCatalogService**：目录 CRUD（个人/系统作用域）、被绑定引用的删除保护（400 + 中文提示）、草稿连通测试（未保存的 baseUrl/Key 即可测试）、`用户绑定 > 系统绑定 > 旧配置 > 环境变量` 解析链、revision 推进与解析缓存失效、`attachToRun` 冻结。
- **API**：`/settings/catalog`、`/settings/providers[/{id}/models|test]`、`/settings/bindings/{purpose}`；旧 `/settings/models` 保留兼容。密钥只回显 hint（`****1234`）。
- **运行接入**：`ManusAgentController.chatModelFor` 改走目录解析（含旧配置兜底），run 启动即冻结模型身份到 `agent_run`。交接摘要担心的"图节点直注 ChatModel"经核实已在先前提交解决（`ManusRunRequest.chatModel()` 传递），无需改造。
- **回环/内网放行**：`app.config.allow-local-model-endpoints=true` 时目录接受本机/内网模型地址（桌面专用，网页部署默认保持公网限制）。

### 前端

- `/model-catalog`（`ModelCatalogSettings.vue`）：供应商列表 + 详情编辑 + 模型列表（能力标签、启停）+ 用途绑定（对话/视觉）+ 草稿测试 + 删除确认，全部 `--wb-*` 令牌 + scoped 样式，双主题适配。导航「设置」入口已指向新页。
- 新 API 封装见 `frontend/src/api/index.js`（listModelCatalog 等 10 个函数）。

### 实测

- scratch 库 V1~V4 全过，3.5s 冷启动；API 冒烟全过（创建/绑定/测试/删除保护/解绑后删除）；迁移 SQL 数据搬移实测正确（SYSTEM 与 USER 两路）；浏览器全流程走通（创建 DashScope 供应商→加模型→绑定→截图验收）。

## 阶段 D：桌面独立运行（已交付）

- **回环认证**（`JwtAuthFilter` 扩展）：`X-Aster-Local-Token` 与 `app.desktop.loopback-token` 常量时间匹配 + 回环来源校验 → 本机 admin 身份；未配置时通道完全关闭，网页部署零影响。
- **desktop profile**（`application-desktop.yml`）：禁 API 文档、放行本机模型地址、关闭 Docling 外部依赖（PDF 走内置 iText）。
- **main.cjs 重写**：捆绑 PG 全生命周期（initdb → **服务器启动前** single-user 建库（运行中无法用 --single，这是实测教训）→ pg_ctl 启动 → 退出停止）、动态端口、健康门控（90 秒未就绪展示诊断页+日志路径+重试）、backend/PG 日志落 `userData/logs/`、随机回环令牌与管理员密码注入、保留远程模式与既有设置 IPC。
- **前端免登录**：preload 注入 `localToken`，`getToken()` 桌面模式返回伪令牌过守卫，axios 与 SSE 统一附加 `X-Aster-Local-Token`。
- **捆绑脚本**（`prepare-pgserver.sh`）：下载 Zonky 二进制（阿里云优先，SHA1 校验）→ 从二进制自报版本对齐源码（Zonky 18.6.0 ≠ 源码 18.6，踩坑记录）→ configure + 三个派生头生成 → pgvector 编译安装（`.dylib` 命名、Debian 风格 extension 目录）→ **相对符号链接**去重（绝对链接在临时目录删除后悬空，踩坑记录）→ 产物 **147MB**。
- **实测**：`verify-desktop-chain.sh` 端到端通过——initdb/建库/PG 启动/desktop 后端（V1~V4）/health 200/令牌 200·401·401。向量库保持硬装配（捆绑 PG 已含 pgvector，无需降级路径，避免条件装配级联改造）。

## 阶段 E：打包（开发测试包已产出）

- `electron-builder --mac dmg zip --arm64` 产出 `desktop/release/Aster-0.1.2-arm64.dmg`（337MB，未签名）。
- 无 Apple Developer ID 签名/公证账号，按计划约定**只作为开发测试包**，不宣称公开分发。
- 体积构成：Electron 壳 ~100MB + JAR 122MB + 捆绑 PG 147MB + JRE 54MB；后续可裁 JAR 内依赖与 PG locale 数据进一步压缩。

## 遗留事项

1. **旧页面下线**：`Home.vue`（1072 行）已无入口；`ModelSettings.vue`/`WorkspaceLayout` 下其他设置页仍是旧样式，需按工作台设计逐步迁移到 `--wb-*` 体系。
2. **视觉/嵌入模型**：VISION 绑定目前仅目录存储，业务消费（图片理解）尚未切到目录解析；embedding 仍走 DashScope SDK bean。
3. **升级备份**：desktop 更新前自动备份 pgdata 未实现（阶段 E 签名公证一起做）。
4. **干净机器验收**：需在一台无开发环境的 arm64 Mac 安装 dmg 实测首启（当前在本机模拟链路验证）。
5. 桌面配置 UI（`AgentSettingsMenu.vue`）未适配新的 runtimeState/令牌机制，远程模式配置仍可用。
