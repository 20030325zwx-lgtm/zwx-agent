# Aster 数据中台（data-service）

Aster 的数据中台独立服务：反馈采集、BadCase 归类、隐式信号、一键回流评测集。
独立镜像、独立页面（`/data/`）。

## 能力

- 智能体回答的 👍/👎 一次点击入仓（冻结 question/answer 来源快照），可带快捷标签
- **BadCase 根因归类**：`knowledge_gap / retrieval_miss / prompt_weak / tool_args /
  intent_misroute / refusal_fail / latency / other` + 负责人 + 处理结论
- **治理看板**：反馈总量、回流转化率、按根因分布（好评/差评/已回流）
- **隐式信号**：`adopt`（采纳）/ `edit`（编辑）/ `regenerate` / `copy` / `export`，
  按事件聚合——比点赞密集的质量真值，也用于交叉验证 judge 是否失准
- **一键回流**：好评 → golden 用例，负评 → 回归用例（`critical` 分级），
  根因与处理结论自动写入用例 notes；写入评测中台「线上回流-单轮直调」数据集草稿

## 与评测中台的关系

回流通过评测中台内部接口 `POST /api/internal/eval/backflow`
（`X-Aster-Internal-Token` 鉴权）完成；本服务只拥有 `platform.user_feedback` 与 `platform.user_signal`。

## 主要 API

| 分组 | 接口 |
| --- | --- |
| 反馈 | `POST /api/ai/feedback`（登录用户，`rating/reason/tags/runId`）、`GET /api/ai/feedback`（管理员，可按 `status/rating/source/rootCause` 过滤） |
| 归类 | `PUT /api/ai/feedback/{id}/analysis`（`rootCause/assignee/resolution`）、`POST /{id}/status`、`POST /{id}/convert` |
| 看板 | `GET /api/ai/feedback/stats` |
| 信号 | `POST /api/ai/feedback/signals`（登录用户）、`GET /signals/summary?days=7`、`GET /signals`（管理员） |

## 配置（环境变量）

| 变量 | 说明 |
| --- | --- |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | 主库连接（platform schema） |
| `JWT_SECRET` | 与主站一致（本地验签用户 JWT） |
| `PLATFORM_INTERNAL_TOKEN` | 服务间内部令牌（与主站、eval-service 一致） |
| `ASTER_EVAL_URL` | 评测中台地址，默认 `http://eval-service:8140` |

## 运行

```bash
mvn -DskipTests package
java -jar target/aster-data-service-0.0.1-SNAPSHOT.jar
# 页面：http://127.0.0.1:8141/data/
```

## 构建镜像

```bash
docker build -t aster-data-service:local .
```
