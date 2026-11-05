# 部署 0.7.4（图片提示词优化双风格：写实/漫画）

## 背景与改动

- 用户需求：图片提示词优化按风格区分（写实=贴近照片 / 漫画=二次元插画）。
- 提交 `62d7097`：
  - 后端 `PromptOptimizationService`：IMAGE 模式拆两套规范——
    - 写实：主体质感（皮肤/毛发/瑕疵）、等效焦段与光圈景深、光线方向/色温/阴影软硬、胶片或数码质感、真实感约束（无插画/3D/AI 感）；
    - 漫画：先定子风格（日漫/国漫/美漫/韩漫）、墨线描边与粗细变化、赛璐璐平涂块面阴影、网点/速度线、风格统一约束（无照片质感/3D 渲染）。
  - 新增 `imageStyle` 参数（REALISTIC/COMIC，缺省 REALISTIC，非法值显式拒绝），输出契约 `{"prompt":...}` 不变，仍受 500 字模型上限保护。
  - 前端画布检查器「优化提示词」旁新增「写实/漫画」下拉（仅图片节点），选择随请求提交。
- 在线文档（OpenAI/Google/Midjourney 指南）抓取被重定向或返回异常，规范按业界通行提示词工程实践固化，未引入外部 skill 文件。

## 验证

- `PromptOptimizationModesTest` 7 项通过（含风格归一化与规范关键词断言）。
- 真实接口实测（admin dev token，同一输入"一只橘猫在夕阳下的窗台睡觉"）：
  - 写实输出含 85mm f/1.8、浅景深、胶片颗粒、色温、无插画感等；
  - 漫画输出含日漫风格、墨线描边、赛璐璐、有限色板、网点、无文字气泡等。
- 前端构建通过；线上主资产 `assets/index-Bect6lw-.js` 与本机 dist 一致；公网 health ok。

## 部署

- `aster-backend:0.7.4` + `aster-frontend:0.7.4`（299MB，md5 双端校验一致）；其余服务重打 tag 未重启。
- 无数据库迁移；备份 `.env.bak-2026-11-05-1043`；双层 nginx 已重载。

## 回滚

```sh
cd /opt/aster
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.7.3/' .env
docker compose up -d --no-deps backend frontend
docker exec aster-frontend-1 nginx -s reload
docker exec aster-nginx-proxy nginx -s reload
```
