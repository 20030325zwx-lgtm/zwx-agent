# 部署 0.7.2（供应商连通测试对媒体中转站误报修复）

## 背景

- 用户反馈「KKAI 中转站」测试显示 404 openai_error、「KKAI 视频中转站」显示 400 模型不支持 Chat Completions。
- 前置问题：服务器 DNS 解析不了 `api.kkrich.ltd` 的 CNAME 目标 `cf.xreak.top`（腾讯内网 DNS），已被 SSRF 校验拦截并显示"地址必须是可解析的公网 URL · 0ms"。
  - 已在服务器 `/etc/systemd/resolved.conf.d/99-aster-dns.conf` 增加公共 DNS 上游（223.5.5.5 / 119.29.29.29，腾讯 DNS 作 fallback），重启 systemd-resolved 与 backend。回滚：删除该 drop-in 后重启。
- DNS 修好后暴露真实原因：`ModelConfigurationProbe` 用 chat/completions 探测，而两个中转站下是图片/视频模型（gpt-image-2、sd_2.0_special_720p 等），本就不支持对话接口。

## 本次改动（提交 ebfef3c）

- 探针改两阶段：
  1. OpenAI 兼容先 `GET {base}/models` 验证网络可达与密钥有效（与模型类型无关）；
  2. 再做对话探测；返回"模型不支持 Chat Completions"或中转站包装错误（bad_response_status_code）时判定连接/鉴权成功并说明疑似媒体模型。
- 鉴权失败（401/403）、服务异常（5xx）、网络不可达分别给出明确信息；`/models` 未暴露（404）时退回原对话探测。
- 回归：`ModelConfigurationProbeTest` 5 项（models 计数 / 401 / 404 / 不可达 / 错误分类）。

## 部署

- 仅后端：`aster-backend:0.7.2`（229MB，md5 双端校验一致），前端与其余服务重打 tag 未重启。
- 无数据库迁移；备份 `.env.bak-2026-11-05-1025`（compose 未变）。
- 双层 nginx 已重载。

## 验证结果

- 本地真实接口（dev 密钥 admin token）：provider 14/15 均 `ok=true`，耗时 793ms / 1877ms。
- 生产环境（服务器内 localhost:8080 + 服务器 JWT）：provider 14 `ok=true`（模型列表 3 个）、provider 15 `ok=true`（16 个），消息含"疑似图片/视频模型，请按能力绑定使用"。
- `/opt/aster` backend 0.7.2 healthy；公网健康检查 ok。

## 回滚

```sh
cd /opt/aster
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.7.1/' .env
docker compose up -d --no-deps backend
docker exec aster-frontend-1 nginx -s reload
docker exec aster-nginx-proxy nginx -s reload
```

## 注意事项

- 媒体模型（图片/视频）本就不支持 chat/completions：测试现在会明确"连接成功 + 模型不支持对话接口"，使用时应通过模型列表的「能力」绑定为图片/视频生成，而不是对话模型。
- `/models` 计数为 3（provider 14）与 16（provider 15），可用于核对中转站实际可用模型。
