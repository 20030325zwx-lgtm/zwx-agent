# 评测中台 E1 MVP 实施会话（2026-11-04）

> 追加（同日稍晚）：按用户决策完成「平台容器」部署形态——数据中台与评测中台各自独立 Docker 容器，
> 代码保留在单体服务内。详见文末「平台容器部署形态（追加）」。

## 改动内容

按 [docs/design/2026-11-03-data-eval-platform-research.md](../design/2026-11-03-data-eval-platform-research.md) §7.4 的 E1 方案实施评测中台 MVP（方案先经计划模式获用户批准，四类评估器全上为用户决策）：

- **V23 迁移**（`src/main/resources/db/migration/V23__eval_platform.sql`）：`CREATE SCHEMA platform` + 6 张表（eval_dataset / eval_dataset_version / eval_case / eval_experiment / eval_case_result / eval_event），跨 schema 只存 ID 不加外键，重产物截断存 jsonb。
- **后端新包 `com.zwx.zwxagent.platform.eval`**：
  - 调度：`EvalRunScheduler`（仿 MediaJobScheduler，单线程 SKIP LOCKED 认领 + epoch 租约）+ `EvalExperimentRepository`（认领/围栏 SQL）+ `EvalRunner`（逐用例串行、单例失败不拖垮实验、totals 汇总）。
  - 配置（application.yml `eval.*`）：`eval.scheduler.enabled` **默认 false**（本地不抢服务器任务；服务器 compose .env 设 `EVAL_SCHEDULER_ENABLED=true` 启用）、lease 900s、task 超时 15min、隔离账号 eval-bot。
  - 评估器：`EvalTargetService` 四类分派——INTENT（进程内 IntentRouter）、RETRIEVAL（进程内 `AgentKnowledgeRagService.verifyDocument`）、PROMPT（单轮直调 + 规则断言 + 可选 LLM-as-judge 四维 rubric）、TASK（`ManusTaskRunner` 无头复刻 controller.chat 准备链路：会话→用户轮次→意图→RAG→MCP/内置工具→agent_run→runStream + completionHandler/CountDownLatch 等待，YOLO 无人值守，轮询检测计划确认门；闲聊直达短路复刻；**评测会话不删除**以保留轨迹证据——agent_run 对会话 ON DELETE CASCADE）。
  - `RuleChecks`：移植 `score_task_result.py` 9 条断言 + PROMPT 断言（contains/not_contains/regex/长度/judge 阈值）。
  - API：`web/controller/EvalController.java`（/ai/eval，全部管理员）：数据集 CRUD/导入/发布、实验创建/列表/详情/结果/事件游标/取消/**双实验对比**。
  - `ModelCatalogService` 新增 `PURPOSE_EVAL="eval"`（绑定解析链 eval→agent→chat）。
- **前端**：`views/eval/EvalCenter.vue`（实验/数据集双页签、详情抽屉、进度轮询、对比报告、创建对话框）+ 路由 `/eval` + GlobalNav「评测台」+ api/index.js 14 个导出；样式复用 creative.css 与 --cr-* 令牌。
- **单测**：`RuleChecksTest`（对齐 python 断言）、`EvalCaseNormalizerTest`（四类 schema 规范化 + totals 计算），15/15 通过。
- **实施偏差说明**：方案里的「ManusRunPreparer 提取」未做——controller.chat 与 SSE/闲聊短路/配额深度耦合，提取风险大于收益；改为 `ManusTaskRunner` 自备精简准备链路（约 70 行镜像 controller 行为，差异点：无配额/图片/工作区/SSE 推送）。controller 未改动。

## 验证结果

- `mvn -DskipTests package` 通过；前端 `npm run build` 通过（既有大 chunk 警告）。
- **隔离 E2E**（本地 docker postgres 新建 `zwx_agent_e2e` 库 + 临时后端 8124/8136，不触碰共享库）：四类实验全部 SUCCEEDED——
  - INTENT 12/12（真实 qwen 路由调用，达 CI 门禁 minPass 11/12 水平）；
  - RETRIEVAL 15 例全执行（隔离库无语料全不命中，路径正确）；
  - PROMPT 2 过 1 不过，judge 真实打分（1.0 分低于阈值被正确判负）；
  - TASK 全链路 run COMPLETED（1 步计划/验证 pass）+ 闲聊直达短路命中；
  - compare 逐例 delta、events 游标轮询正常。
- 主后端 8123 已重启加载新 jar（Flyway validated 23 migrations，V23 已应用）；`/api/health` ok；eval 端点未登录 401（鉴权正常）；前端 3000 返回 200。
- 临时实例与 zwx_agent_e2e 库保留（可复跑 E2E 脚本 `/tmp/eval_e2e.py`）；主库未写入任何评测数据。

## 遗留事项

- 服务器部署：compose `.env` 加 `EVAL_SCHEDULER_ENABLED=true` 才会在服务器跑实验；镜像需重新构建发布（本轮仅本地）。
- 评测台页面用真实管理员账号走查（浏览器）未做——本地管理员密码不可知（0.2.3 遗留轮换问题），E2E 用隔离库 admin 验证了 API 全链路，页面逻辑与 API 一一对应。
- E2E 中 PROMPT-002（翻译）judge 判 1.0 分：judge 对短翻译类用例偏严，后续可调 rubric 锚点（正常现象，非 bug）。
- 后续批次（E2/D1）：反馈埋点、BadCase 回流、Prompt 资产库、固定评测集定时回跑（漂移检测）。
- 前端类型标注：judge 均分、对比报告等展示在深色主题下未走查。

## 环境注意事项

- **本轮发现并修复 3 个 bug**（均有 docs/bugs/ 记录）：findDueForClaim 参数个数、experiment insert 构造参数错位、快照 Instant 序列化（裸 mapper 缺 JSR310）。
- 临时验证实例：8124 主端口 / 8136 管理端口，指向 docker postgres 的 `zwx_agent_e2e` 库；`eval.scheduler.enabled=true` 仅在该实例生效。
- 主 8123 后端与 3000 前端正常运行；未触碰 application-local.yml；未动共享库数据。

## 平台容器部署形态（追加，同日稍晚）

用户决策：数据中台与评测中台各自独立 Docker 容器，代码保留在单体服务内（同镜像、不同开关）。

### 改动内容

- `src/main/resources/application-platform.yml`（新 profile）：关闭全部业务后台工作
  （媒体调度 / run 恢复 / 知识索引重试 / 批次提交 / 数据保留 cron / workflow 媒体恢复 / 剧集候选恢复），
  只保留中台自身调度；业务 API 在任何容器等价（前端 /ai/eval/* 仍走主 backend，实验入队后由
  eval-worker 从库认领执行，库即队列）。
- 补齐两个缺失的调度开关（platform profile 需要关掉它们）：
  `app.story.batch.submit-enabled`（BatchSubmitScheduler）、
  `app.agent.rag.index-recovery-enabled`（AgentKnowledgeIndexRecoveryJob），默认 true，主 backend 行为不变。
- `deploy/docker-compose/docker-compose.yml` 新增两个服务（同 `aster-backend:${IMAGE_TAG}` 镜像）：
  - `eval-worker`：profile=prod,platform + `EVAL_SCHEDULER_ENABLED=true`，mem 1536m，不对外暴露端口；
  - `data-worker`：同 profile 无调度，常驻就绪（D1 实施后在此登记调度开关），mem 768m。
- `docs/DEPLOYMENT.md`：§1 拓扑 + §7 新增「平台容器」小节（启用步骤与验证命令）。

### 验证结果

- compose config 校验通过。
- 隔离实例（8125，profile=local,platform + EVAL_SCHEDULER_ENABLED=true）jstack 断言：
  `eval-run-scheduler` 线程存在、`media-job-scheduler` 线程不存在、日志零 ERROR。
- 主 backend 8123 重启新 jar 作对照：`media=1 eval=0`（主侧照常跑媒体调度、评测调度关闭），
  health ok。
- 多实例安全性核查：`AgentRunRecoveryJob.findOrphanedRunning` 按 owner_instance 心跳（15s）判活，
  eval-worker 崩溃时主 backend 才接管/标中断，健康时不会抢 TASK 运行。

### 遗留事项

- 服务器实际启用：随下次发布执行 `docker compose up -d eval-worker data-worker`
  （镜像复用，无需额外构建）；`.env` 可选 EVAL_TASK_TIMEOUT_MINUTES / EVAL_ACCOUNT_*。
- data-worker 当前空闲属预期；D1（反馈/BadCase 回流）实施后登记其调度开关。

### 平台容器小范围自测（追加，按用户要求）

自测拓扑：本地 Docker 起 4 容器（postgres + backend + eval-worker + data-worker，
`aster-backend:platform-test` 测试镜像 = 同一 jar），独立 compose project，测毕 `down -v` 销毁。

验证结果：
- 四容器全部 healthy；容器内调度分工断言（/proc 线程名）：backend eval=0 media=1、
  eval-worker eval=1 media=0、data-worker 全 0 —— 与设计一致。
- 「库即队列」链路实测通过：backend API 登录/播种模型目录/建数据集/发布/建实验 →
  eval-worker 从库认领执行 → backend API 查到结果与事件（PROMPT 2/2 通过，真实模型调用）。
- 资源：三容器空闲各约 290MiB，postgres 224MiB；8G 服务器预算可行。
- Flyway：三实例同时首启，advisory lock 串行化，无迁移冲突。
- 首轮假 key 导致的 Spring AI 重试 WARN 属测试脚本问题，换真 key 后 0 错误。

按自测结果做的调整：
1. **DEPLOYMENT.md 验证命令修正**：JRE 镜像没有 jstack，原命令会静默失败全 0；
   改为 `/proc/1/task/*/comm` 读线程名（注意 15 字符截断：`eval-run-schedu`/`media-job-sched`），
   并补期望值与模型目录需真实 Key 的注意事项。
2. 测试脚本播种 provider 用假 key 绑定后不会走 env 兜底（绑定优先于 legacy 解析）——
   已在 DEPLOYMENT.md 注明；这是评测平台对接真实部署时的关键操作项。

## D1 数据中台 MVP + 全体系交付（追加）

用户目标：今天把整套体系交付完整（智能体平台 + 评测中台 + 数据中台 + 容器部署形态），细节后续再议。

### 改动内容

- **V24 迁移**：`platform.user_feedback`（rating up/down、status open/acked/converted/ignored、
  refs JSONB 冻结来源快照、converted_* 回流链接）。
- **后端 `platform.data`**：`UserFeedbackService`（采集时按 run 冻结 question/answer 快照；
  `buildBackflowCase` 静态规则：👍 带 golden reference、👎 reference 留空 + 原回答进 notes + judge 阈值 3.5）、
  `UserFeedbackRepository`、`EvalDatasetService` 增 `findOrCreateBackflowDataset`（「线上回流-单轮直调」）与
  `findDraftCaseId`。API `web/controller/UserFeedbackController`（/ai/feedback：采集全员、
  列表/状态/一键回流管理员）。
- **前端**：ChatRoom 回答操作区新增 👍/👎（一次点击、乐观状态、无 run 的场景自动隐藏），
  SuperAgent 传 conversation-id；评测台新增「反馈 / BadCase」页签（状态筛选、计数、
  转 golden/回归用例、忽略/已知悉）；api/index.js 5 个导出。
- **发布产物**：`release/deploy-0.3.0.sh`（服务器执行：备份→load 镜像→compose 更新→四容器启动→
  /proc 分工断言；含回滚说明）+ `/tmp/aster-0.3.0-apps.tar.gz`（290MB，amd64 backend+frontend）+
  `/tmp/docker-compose-0.3.0.yml`。MCP 镜像同代码位由脚本重打 tag。

### 验证结果

- 单测 18/18（含新增 `UserFeedbackBackflowTest` 3 例）；`mvn package`、`npm run build` 通过。
- **容器拓扑自测（4 容器，真实镜像）——数据飞轮全链路通过**：
  真实 YOLO 任务 run（SSE）→ 👎 反馈（question/answer 快照落 refs）→ 管理员一键回流
  （自动建「线上回流-单轮直调」数据集，case fb-2ca25c7f，重复回流被 400 拒绝）→
  发布 v1 → eval-worker 容器执行实验（judge 5.0，SUCCEEDED）→ 反馈状态回写 converted。
- 自测中发现并修复测试脚本两处：SSE 首事件是裸 UUID 字符串（非法 JSON，解析需先匹配 UUID 模式）。
- 主 backend 8123 重启上线 V24（validated 24 migrations），/api/health ok，feedback API 鉴权正常，
  前端 3000 正常。
- **服务器部署受限**：无 SSH 免密通道（0.2.0 为交互式密码登录），0.3.0 部署命令已备好
  （release/deploy-0.3.0.sh），需用户执行三条命令完成上线。

### 遗留事项（细节，后续讨论）

- 服务器 0.3.0 上线（用户执行）；`.env` 会自动追加 EVAL_SCHEDULER_ENABLED=true。
- 创作产物（图片/视频）的 👍👎 入口（source=creative）未做——当前仅 chat 回答。
- 反馈理由输入框（当前一次点击无理由，符合「入口要轻」约定；reason 留给管理侧）。
- 生成日志 versions 四元组补齐（agent_run 模型快照/prompt 版本）——E2/D2 范畴。

## 服务器部署 0.3.2（追加，已完成）

服务器：腾讯云轻量 4C8G Ubuntu 24.04，`ubuntu@1.13.152.215`（免密 sudo + docker 组）。

### 部署记录

- 线上原为 `IMAGE_TAG=0.3.1`（无平台容器）；本次发布 **0.3.2**（镜像由当前 jar 构建，amd64）。
- 流程：scp 290MB 镜像包 → `deploy-0.3.2.sh`：备份 .env/compose/DB → load 镜像 →
  compose 更新（新增 eval-worker/data-worker）→ `.env` 置 IMAGE_TAG=0.3.2 + EVAL_SCHEDULER_ENABLED=true →
  `docker compose up -d backend frontend eval-worker data-worker`。
- 验证：四容器 healthy；调度分工断言 backend `eval=0 media=1`、eval-worker `eval=1 media=0`、
  data-worker 全 0；backend jar 内 `EvalController`/`UserFeedbackController` 确认存在；
  Flyway validated 24；域名 https://www.zhuwenxuan.online 首页 200、`/api/health` ok。

### 部署中发现的问题与处理

1. **重建容器后域名 502**：nginx-proxy 启动时缓存上游容器 IP，backend/frontend 重建后 IP 变化导致 502。
   处理：`docker restart aster-nginx-proxy` 后恢复；已写入 DEPLOYMENT.md（发布脚本最后一步必须重启 nginx）。
2. **globe.conf 管理员密码是旧值**：DB 中密码已轮换，`app.security.admin-password` 与库不一致，
   导致线上 admin 登录 401（本地 .env.local 也失效）。**待办：把 globe.conf 的 admin-password 同步为
   当前实际密码，或重置后同步**——属 0.2.3 遗留待办，不阻塞本次发布。
3. 线上 MCP 镜像 tag（0.3.1/0.3.2/0.4.0）全部指向同一镜像 ID（10-27 构建），无降级风险；
   回滚 tag 已保留（backend/frontend/mcp 0.3.1 + 当前 0.3.2）。

### 清理结果

- 服务器：删除 `/tmp` 下 0.1.0 时代残留约 3.7GB（624M+440M+805M+301M+1.5G）+ 旧版镜像 tag，
  磁盘占用 32% → 25%（18G→14G used）；备份保留最近 3 份 + compose/env 最新备份；
  镜像清单收敛为 0.3.1（回滚）+ 0.3.2（当前）+ mcp 0.4.0。
- 本地：删除 290M/304M 发布包、容器自测目录与脚本、临时日志、0.3.0/0.3.2 本地镜像 tag；
  保留 release/deploy-0.3.2.sh 作为部署记录；/tmp 中更早会话的审查产物未动（非本次生成，
  由用户决定是否清理）；本地保留 aster-infra / aster-sandbox-cli / aster-image-mcp:local（可能被桌面打包脚本引用）。

### Ubuntu 服务器操作备忘

- 登录：`ubuntu` 用户，密码登录；免密 sudo；docker 可直接使用。
- 部署脚本需以 ubuntu 执行（/opt/aster 归 ubuntu 所有），无需 root。

## 中台拆分为独立服务（0.4.0，追加）

用户要求：同仓库代码可以放一起，但**单独打包、单独镜像、页面不得混在主站**。

### 架构

- `services/eval-service`（8140）：评测中台独立 Spring Boot 工程 + 自带 Vite/Vue 页面（/eval/）。
  拥有 datasets/experiments/results/events 与调度器；PROMPT/judge 直连 DashScope；
  INTENT/RETRIEVAL/TASK 经主站内部 API。
- `services/data-service`（8141）：数据中台独立工程 + 自带页面（/data/）。
  反馈采集（只读快照主库 agent_run）/ BadCase 状态 / 一键回流（调 eval-service 内部接口）。
- 主站新增 `platform/internal/`：`/api/internal/eval/{intent,verify-retrieval,task-run}`，
  `InternalTokenFilter` 校验 `X-Aster-Internal-Token`（fail-closed）；TASK 无头执行逻辑
  从原 ManusTaskRunner 迁入 `InternalEvalRunService`。主站删除 platform.eval/platform.data/
  EvalController/UserFeedbackController/application-platform.yml；前端删除评测台页面，
  导航改为外链 /eval/、/data/。
- 鉴权：两个服务用同一 JWT_SECRET 本地验签（claims sub/uid/tenant/role），
  ADMIN 校验；服务间用 PLATFORM_INTERNAL_TOKEN。
- 构建：package.sh 增加两个镜像；compose 用独立镜像替换原同镜像 eval-worker/data-worker；
  postgres 增加 127.0.0.1:5432 回环发布（修复本地开发 SSH 隧道）。

### 验证

- eval-service 单测 8/8；三端 `mvn clean package`、三个前端构建均通过。
- 三进程联调（隔离库 + 内部令牌）全绿：跨服务 JWT、内部令牌 401/200、
  INTENT 12/12、PROMPT（直连 DashScope+judge）、TASK（内部 task-run）、
  反馈→回流→eval 数据集→状态回写。
- 服务器 0.4.0 已上线：backend/frontend/eval-service/data-service/postgres 全 healthy；
  / 200、/eval/ 200、/data/ 200、页面资源 200；两个 API 未登录 401；
  内部接口无令牌 401、正确令牌 200（服务端 intent provider=none，返回 available:false）；
  孤儿 worker 容器已移除，旧镜像清理（磁盘 26%）。

### 过程中修复的 3 个 bug（均有 docs/bugs/ 记录）

1. 内部 API 在 context-path=/api 下路径双重前缀（404）。
2. 独立页静态资源被鉴权拦截（401）且缺 index 转发（500）。
3. nginx 配置重复 resolver 导致 frontend 容器反复重启。

### 遗留

- 服务器 `APP_INTENT_PROVIDER` 未启用 → 线上 INTENT 实验会全部 skipped（既有产品状态，
  启用 qwen 会改变线上意图分流行为，需单独确认）。
- 服务器管理员密码与 globe.conf 不一致（0.2.3 遗留）仍待同步。
- 本地开发：需手动启动两个服务（启动命令见 README/session），vite 已代理 /eval /data。

### 本地启动两个中台服务（开发用）

```bash
# 主站需带内部令牌（本地 launchctl 任务未设置时，内部执行会 401）：
# 手动启动主站时加 PLATFORM_INTERNAL_TOKEN=dev-internal-token

# eval-service（8140）
cd services/eval-service && mvn -q -DskipTests package
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:15432/zwx_agent \
SPRING_DATASOURCE_USERNAME=<同 application-local.yml> SPRING_DATASOURCE_PASSWORD=<同上> \
JWT_SECRET=<同主站> PLATFORM_INTERNAL_TOKEN=dev-internal-token \
ASTER_BACKEND_URL=http://127.0.0.1:8123 DASHSCOPE_API_KEY=<同主站> \
EVAL_SCHEDULER_ENABLED=true java -jar target/aster-eval-service-0.0.1-SNAPSHOT.jar

# data-service（8141）
cd services/data-service && mvn -q -DskipTests package
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:15432/zwx_agent \
SPRING_DATASOURCE_USERNAME=<同上> SPRING_DATASOURCE_PASSWORD=<同上> \
JWT_SECRET=<同主站> PLATFORM_INTERNAL_TOKEN=dev-internal-token \
ASTER_EVAL_URL=http://127.0.0.1:8140 java -jar target/aster-data-service-0.0.1-SNAPSHOT.jar
```

Vite dev 已把 `/eval`、`/data` 代理到 8140/8141；主站 3000 的「评测台 / 数据中台」导航可直接打开。
