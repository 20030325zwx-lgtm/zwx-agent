# 部署 0.7.8（北斗七星标识 + 七星加载动效）

## 背景与改动

- 用户反馈原产品标识（对称四角星/深色底）不好看，要求参照 Muse 星空质感、改为北斗七星，并让所有页面加载动效使用七星依次亮起的效果，背景要浅色。
- 提交 `025e331`（前端 + 桌面图标）：
  - 标识：北斗七星连线（天枢→天璇→天玑→天权→玉衡→开阳→摇光，含开阳辅星细节），浅薰衣草底（#EDEFF9）+ 靛蓝星点（#3A3D7D）；`--wb-mark-bg/fg` 主题变量同步改为浅色（深色主题也用浅底）。
  - 加载动效：新增 `AsterLoader`（七星按斗柄顺序依次亮起，尊重 prefers-reduced-motion），替换首页、创作库、工作流列表/编辑器、技能与活动抽屉、媒体预览的「加载中…」；按钮内联 `Spinner` 改为单星闪烁；`index.html` 首屏启动页（Vue 挂载前）使用同一七星动画。
  - 同步 favicon.svg / app-icon.svg / apple-touch-icon.png / 桌面 icon.svg、icon.icns。

## 部署过程与脚本修复（一键脚本首次实战）

- 首次通过 `deploy/scripts/deploy-to-server.sh` 发布，暴露并修复两个脚本 bug：
  1. `docker build -t "$name"` 丢失版本 tag → 上传了旧镜像（线上仍为 0.7.4 资产）；已改为 `-t "${name}:${version}"` 并增加 `docker image inspect` 校验。
  2. 发布后重打 tag 循环包含本轮发布的服务，用旧版本覆盖了刚载入的新镜像；已改为仅对未发布服务重打 tag。
  3. 资产哈希不一致由 warn 改为 die（发布失败不落状态，下次重试）。
- 修复后重发，版本 0.7.8（后端 0.7.8 与前端 0.7.8 均重建发布）。

## 验证结果

- 公网 `GET /api/health` → ok；首页 200。
- 线上主资产 `assets/index-BfTHdeYK.js` 与本机 dist 完全一致（发布生效）。
- 公网登录页实测：浅色底北斗七星标识正常显示；首屏启动动画（无 JS 时）显示七星依次亮起。

## 回滚

```sh
cd /opt/aster
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.7.7/' .env
docker compose up -d --no-deps backend frontend
docker exec aster-frontend-1 nginx -s reload
docker exec aster-nginx-proxy nginx -s reload
```

## 注意

- 0.7.6/0.7.7 两个中间版本因脚本 bug 实际未生效（镜像内容错误/被覆盖），当前有效版本为 0.7.8。
- 桌面端 icon.icns 已重新生成，下次桌面打包自动使用新标识。
