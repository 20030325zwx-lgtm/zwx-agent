# Aster 评测中台（eval-service）

Aster 的评测中台独立服务：数据集版本化、实验执行与对比报告。与主站（agent 平台）同属 Aster 体系，
但独立镜像、独立页面（`/eval/`）、独立打包。

## 能力

- **四类评估器**（策略接口，新增类型只需加一个 Bean）：
  `intent`（意图路由）、`retrieval`（RAG 检索命中）、`prompt`（单轮直调 + LLM-as-judge）、
  `task`（全链路智能体任务）
- **被测对象（target）是一等公民**：实验 = 数据集版本 × 被测对象。
  INTENT/RETRIEVAL/TASK 在主站执行，只能测 `live`；PROMPT 可指定 `model` 或 `prompt` 资产版本。
- **Prompt 资产库**：`key@version` 寻址，版本不可变；创建实验时把 Prompt 正文冻结进快照，
  历史实验永远可复现。
- **数据集**：导入 / 单条增删改 / 标签 / `normal|critical` 分级 / 发布不可变版本 / 从历史版本 fork 回草稿
- **实验**：并发执行、断点续跑（复用已完成结果）、单例失败重试、预算护栏（最多用例 / 成本上限 / judge 抽样）
- **结果**：逐例断言、judge 四维打分、token 与成本计量
- **报告**：按标签 / 断言 / judge 维度聚合、同数据集趋势、门禁判定（`/gate` 返回 `{pass, reasons}`）、CSV 导出
- **双实验对比**：逐例 delta + 通过率/成本/延迟差

## 与主站的关系

- 只做编排与评分；`intent` / `retrieval` / `task` 通过主站内部 API
  （`/api/internal/eval/**`，`X-Aster-Internal-Token` 鉴权）执行，不重复实现智能体运行时
- `prompt` / judge 直连 DashScope 兼容接口
- 共有 PostgreSQL 的 `platform` schema（V23/V25 迁移由主站维护）

## 主要 API

| 分组 | 接口 |
| --- | --- |
| 数据集 | `GET/POST /api/ai/eval/datasets`、`PUT/DELETE /datasets/{id}`、`POST /datasets/{id}/cases/import`、`GET/POST /datasets/{id}/cases`、`PUT/DELETE /datasets/{id}/cases/{externalId}`、`POST /datasets/{id}/publish`、`POST /datasets/{id}/versions/{v}/fork`、`GET /datasets/{id}/trend` |
| 实验 | `POST /experiments`（target/concurrency/budget）、`GET /experiments`、`GET /experiments/{id}`、`POST /experiments/{id}/cancel`、`POST /experiments/{id}/resume`、`GET /experiments/compare` |
| 报告 | `GET /experiments/{id}/report?groupBy=tag\|check\|dimension`、`/gate`、`/export`、`/results`、`/events` |
| 内部回流 | `POST /api/internal/eval/backflow`（`X-Aster-Internal-Token`，可选 `datasetId`/`tags`） |
| Prompt 资产 | `GET/POST /api/ai/eval/prompts`、`GET /prompts/{id}`、`POST /prompts/{id}/versions` |

## 配置（环境变量）

| 变量 | 说明 |
| --- | --- |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | 主库连接（platform schema） |
| `JWT_SECRET` | 与主站一致（本地验签用户 JWT） |
| `PLATFORM_INTERNAL_TOKEN` | 服务间内部令牌（与主站、data-service 一致） |
| `ASTER_BACKEND_URL` | 主站地址，默认 `http://backend:8123` |
| `DASHSCOPE_API_KEY` / `EVAL_MODEL` | PROMPT 评测与 judge 模型（默认 qwen-plus） |
| `EVAL_SCHEDULER_ENABLED` | 是否执行实验调度（worker 设为 `true`） |
| `EVAL_CASE_RETRY` | 单例评测器调用失败的重试次数（默认 1） |
| `PLATFORM_CI_TOKEN` | CI 只读令牌：只放行 `GET /experiments**`（gate/report/export），留空禁用 |
| `PLATFORM_CI_TENANT` | CI 令牌对应的租户，默认 `default` |

模型价格表在 `application.yml` 的 `app.eval.pricing`，按供应商公示价维护；成本拿不到时为 `null`，不记为 0。

## 运行

```bash
mvn -DskipTests package
java -jar target/aster-eval-service-0.0.1-SNAPSHOT.jar
# 页面：http://127.0.0.1:8140/eval/
```

## 构建镜像

```bash
docker build -t aster-eval-service:local .
```

## 测试

```bash
mvn test   # RuleChecks / 归一化 / 汇总口径
```
