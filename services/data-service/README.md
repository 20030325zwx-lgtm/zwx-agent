# Aster 数据中台（data-service）

Aster 的数据中台独立服务：用户反馈采集、BadCase 库、一键回流评测集。独立镜像、独立页面（`/data/`）。

## 能力

- 智能体回答的 👍/👎 一次点击入仓（冻结 question/answer 来源快照）
- BadCase 管理：状态流转（待处理 / 已知悉 / 已忽略 / 已回流）
- 一键回流：好评 → golden 用例，负评 → 回归用例，写入评测中台的「线上回流-单轮直调」数据集草稿

## 与评测中台的关系

回流通过评测中台内部接口 `POST /api/internal/eval/backflow`
（`X-Aster-Internal-Token` 鉴权）完成；本服务只拥有 `platform.user_feedback`。

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
