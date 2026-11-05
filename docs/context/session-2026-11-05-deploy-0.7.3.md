# 部署 0.7.3（画布运行按钮防重复提交）

## 背景与改动

- 用户反馈：画布「生成当前节点」按钮只在提交瞬间显示「提交中」，任务开始执行后按钮恢复可点，可反复点击重复提交（重复计费）。
- 提交 `d2b7555`（仅前端）：
  - 新增 `runActive` 状态（RUNNING / WAITING_EXTERNAL / WAITING_SELECTION）：运行期间按钮显示「正在执行」并禁用，运行结束自动恢复；
  - 检查器「执行到此节点」同步禁用并提示等待；「停止」按钮复用同一判断；
  - 冒烟测试更新：运行后断言「正在执行 + 禁用 + 提示」，完成后断言标签恢复。

## 部署

- 仅前端：`aster-frontend:0.7.3`（70MB，md5 双端校验一致）；后端沿用 0.7.2 镜像（重打 0.7.3 tag 未重启）。
- 无数据库迁移；备份 `.env.bak-2026-11-05-1030`。
- 双层 nginx 已重载。

## 验证结果

- 冒烟测试通过（含新交互断言：运行中按钮禁用显示「正在执行」、结束后恢复）。
- 线上主资产 `assets/index-B6JlTLPh.js` 与本机 dist 一致；公网 health ok、首页 200。

## 回滚

```sh
cd /opt/aster
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.7.2/' .env
docker compose up -d --no-deps frontend
docker exec aster-frontend-1 nginx -s reload
docker exec aster-nginx-proxy nginx -s reload
```
