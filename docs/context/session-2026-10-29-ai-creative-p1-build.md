# 会话摘要：AI 创作 P1 实施（C00–C16，进行中）

## 执行规则回顾

- 按 docs/design/2026-10-29-ai-creative-implementation.md 任务卡推进，每批更新台账（§3）与验收记录 docs/test/results/2026-10-29-ai-creative-p1.md。
- 不自动 commit/push、不重启共享服务；真实收费调用先征求授权；Mock 不冒充真实验收。

## 已完成

### C00 基线与首个模型契约（A01 PASS）

- 基线 HEAD `94a53e8`；工作区差异＝本模块文档 + 其他会话副本文件（保留不动）。
- 复用既有实现：`PURPOSE_IMAGE`/`resolveImage`（前一会话已交付）；迁移 V1–V7，新增从 V8 起。
- 产出 docs/design/2026-10-29-ai-creative-model-contract.md：DashScope 文生图提交/查询契约、
  状态→Accepted/Completed/Rejected/Unknown 映射、脱敏响应样本、5 个未知项（U1–U5）及门禁影响。

### C01 数据迁移与领域契约（A02 PASS；A05 数据库约束部分覆盖）

- V8__creative_p1_tables.sql：creative_project/creative_draft/media_model_profile/media_job/
  media_job_attempt/media_job_event/media_job_output/creative_asset/creative_asset_link/
  media_job_asset_ref/media_usage_record/media_provider_quota 共 12 表 + 索引 + 默认配额。
- Java：creative/domain（10 个枚举、状态机、快照与行记录）+ repository（7 个 JdbcTemplate 仓库）
  + support/CreativeJson（null 安全）。
- 测试：MediaJobStatusTest（状态机 4 例）、CreativeMigrationTest（隔离库：空库迁移、V7→V8 升级保留
  旧数据、重复迁移无变化、跨用户外键 23503、幂等唯一键 23505）。

### C02 图片模型目录与能力（A03 PASS；A04 待 C04 Mock）

- ModelCatalogService：resolveImage 改为 resolveImageBinding（返回 config+providerId+modelRowId）；
  CAPABILITIES 增加 image_generation。
- MediaModelResolver：显式 modelRowId 优先 → 图片专用绑定 → 未绑定抛 MODEL_NOT_CONFIGURED（不回退 chat）；
  DashScope 生图绑定自动幂等补 media_model_profile（adapter=dashscope-text2image，VERIFIED）。
- ParameterSchema（dashscope 文生图 P1 schema）与校验：未知键/缺必填/类型/取值越界全部拒绝（BR07）。
- 测试：ParameterSchemaTest（4 例）、MediaModelResolverTest（4 例，PG）。

## 验证结果

- `mvn test -Dtest=MediaJobStatusTest,CreativeMigrationTest` → 7/7 通过。
- `mvn test -Dtest=ParameterSchemaTest,MediaModelResolverTest` → 8/8 通过。
- `mvn test`（全套，354+）→ 通过；V8 已应用到开发库（仅增量表）。

## 实施事故与教训

- 本会话曾把新 Java 文件写到 `src/main/java/com/zhuwenxuan/...`（包前缀笔误），
  并出现过“写入成功但文件随后消失”的现象；已全部迁移到 `com/zwx/zwxagent/creative` 并全局修正包名。
  教训：批量建文件后立刻 `ls` 复核 + 编译验证，再继续后续步骤。
- pgjdbc 对 Instant 需要 Timestamp.from 显式转换；JSONB 列 NULL 读取需 null 安全反序列化。

### C03 项目、草稿与归属（A05/A06 部分覆盖；全量待 C04/C06）

- CreativeProjectService：创建/查询/游标列表/更新（名称、描述、归档，乐观锁，仅 ACTIVE→ARCHIVED）。
- CreativeDraftService：创建（项目须 ACTIVE，锁项目行互锁）、读取、更新（expectedVersion → 409）、列表。
- CreativeProjectController（/api/ai/creative/projects|drafts）；ApiExceptionHandler 增加
  创作冲突 409 与领域异常（MODEL_NOT_CONFIGURED→400、UNSUPPORTED_PARAMETER→422）。
- 测试 CreativeProjectServiceTest（6 例，PG）：隔离、草稿乐观锁 409、归档拒绝新草稿、归档不可逆、
  名称长度校验、仓库往返。`mvn test -Dtest=CreativeProjectServiceTest` → 6/6 通过。


### C04 任务受理、幂等、快照（A07/A08/A09 PASS）

- MediaJobService：结构校验（operation/参考图拒绝/提示词上限 500）→ 幂等预检（锁项目行事务内，
  同键同内容复用、不同内容 409）→ 模型解析（BR03 不回退 chat）+ schema 校验 →
  受理事务（锁项目校验 ACTIVE + 排队配额 + 插入 QUEUED + JOB_CREATED 事件，同事务）。
- 插入冲突在事务外回读（PG 事务失败后不可继续查询）；并发由项目行锁天然串行化。
- CreativeJobController：POST /jobs（新建 202/复用 200，Idempotency-Key 必填）、GET /jobs/{id} 基础详情。
- 配额：USER max_queued 默认 20，media_provider_quota 行可覆盖；超限 429 不落库。
- 测试 MediaJobServiceTest（6 例，独立测试用户 + 专用 provider/model/binding，无真实调用）：
  A07 20 并发 1 job 1 事件；A08 改内容/换模型 409、复用冻结快照；A09 事件故障整体回滚 + 配额 429。
- 注意：image_generation 绑定的模型行会被自动补档案且 schema 由适配器刷新（revision 递增）。

## 遗留事项（C04 后更新）

- C01–C04 状态 IN_REVIEW（A07–A09 已 PASS；A05/A06 全量待 C06/C08 实体补齐）。
- 下一任务：C05（租约调度、配额互锁、重启恢复、Mock provider）；之后 C07 供应商适配与入库。
- 未提交：C00–C04 全部改动在工作区，等待用户授权提交。


### C05 租约调度、限流、恢复（A10/A11/A12/A13 PASS）

- MediaProviderAdapter 接口 + MockMediaProviderAdapter（进程内、可脚本化、记录 submit/query 次数）；
  生产适配器在 C07 注册（按 profile.adapter_key 路由）。
- MediaJobScheduler：认领（SKIP LOCKED + 租约 epoch）→ 按状态处理（QUEUED 提交、RUNNING 查询、
  FETCHING 入库、CANCEL_REQUESTED 取消）；配额按 GLOBAL→PROVIDER→USER 行锁顺序检查；
  全部写回带 version+epoch fencing；后台循环默认关闭（creative.scheduler.enabled）。
- MediaJobRecoveryService（recover()）：SUBMITTING 无 taskId 且租约过期 → RECONCILING；
  带 taskId 的任务恢复查询；不自动重发提交。
- Mock 入库 MockAssetIngestService（C06 换真实存储实现）。
- 测试 MediaJobSchedulerTest（5 例，PG/Mock）：A10 双实例并发配额、A11 fencing、A12 恢复不重发、
  A13 提交不明不重发。`mvn test -Dtest=MediaJobSchedulerTest` → 5/5 通过。
- 教训：text block 尾随空格会被剥离，SQL 拼接 AND 时注意；provider_id 为 BIGINT，
  作用域统计参数不要传字符串（bigint = varchar 报错）。

## 遗留事项（C05 后更新）

- C00–C05 完成（C01–C04 IN_REVIEW：全量验收在 C16 复核）；A14–A40 待 C06+。
- 下一任务：C06（文件存储与素材生命周期，OSS/本地适配）；之后 C07 供应商提交与结果入库。
- 未提交：全部改动在工作区，等待用户授权提交。


### C06 文件存储与素材生命周期（A14/A15/A16 PASS；A17 部分覆盖）

- MediaStorage 接口 + OssMediaStorage（复用 OssClientProvider）+ LocalMediaStorage（临时文件原子 rename，
  resolve 做越界校验）+ MediaStorageConfig（creative.storage.backend=oss|local 路由）。
- CreativeAssetService/Controller：上传（签名嗅探 PNG/JPEG/WebP、20MiB、40MP、伪造拒绝，失败不残留 PENDING）、
  内容读取（归属授权）、收藏/改名、软删除（活动任务引用 409）、游标列表。
- ImageInspector：PNG/JPEG 头解析尺寸 + WebP VP8X 解析。
- 测试 CreativeAssetServiceTest（6 例，独立服务栈 + 本地存储 + 独立 JDBC 连接）全绿；
  OSS 真实往返待 C16 真实环境补验；缩略图推迟到 C11 界面批次。

## 遗留事项（C06 后更新）

- C00–C06 完成（C01–C04/C06 IN_REVIEW：全量验收在 C16 复核）；A18+ 待 C07。
- 下一任务：C07（DashScope 适配器 + 产物入库，含 HTTP Mock 供应商）。
- 未提交：全部改动在工作区，等待用户授权提交。

## 环境注意事项

- application-local.yml 未读取密钥内容（仅测试解析 datasource 结构，不输出）。
- 本机 @SpringBootTest 首次上下文加载约 10 分钟，属环境现象；CI 需观察。
- 保留其他会话副本文件；迁移 V8 已应用，下一个新迁移为 V9。
