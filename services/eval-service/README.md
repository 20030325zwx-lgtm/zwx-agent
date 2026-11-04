# Aster 评测中台（eval-service）

Aster 的评测中台独立服务：数据集版本化、实验执行与对比报告。与主站（agent 平台）同属 Aster 体系，
但独立镜像、独立页面（`/eval/`）、独立打包。

## 能力

- 四类评估器：`intent`（意图路由）、`retrieval`（RAG 检索命中）、`prompt`（单轮直调 + LLM-as-judge）、
  `task`（全链路智能体任务）
- 数据集 / 版本（发布不可变）/ 用例（草稿与发布分离）
- 实验：数据集版本 × 被测对象快照，异步调度（库内队列 + 租约认领），逐例结果与汇总
- 双实验对比报告

## 与主站的关系

- 只做编排与评分；`intent` / `retrieval` / `task` 通过主站内部 API
  （`/api/internal/eval/**`，`X-Aster-Internal-Token` 鉴权）执行，不重复实现智能体运行时
- `prompt` / judge 直连 DashScope 兼容接口
- 共有 PostgreSQL 的 `platform` schema（V23 迁移由主站维护）

## 配置（环境变量）

| 变量 | 说明 |
| --- | --- |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | 主库连接（platform schema） |
| `JWT_SECRET` | 与主站一致（本地验签用户 JWT） |
| `PLATFORM_INTERNAL_TOKEN` | 服务间内部令牌（与主站、data-service 一致） |
| `ASTER_BACKEND_URL` | 主站地址，默认 `http://backend:8123` |
| `DASHSCOPE_API_KEY` / `EVAL_MODEL` | PROMPT 评测与 judge 模型（默认 qwen-plus） |
| `EVAL_SCHEDULER_ENABLED` | 是否执行实验调度（worker 设为 `true`） |

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
