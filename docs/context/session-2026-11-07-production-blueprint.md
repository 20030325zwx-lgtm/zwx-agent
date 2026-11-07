# 会话摘要：制作蓝图落画布（2026-11-07）

## 需求与方案

用户希望视频制作最后一步能把五阶段编排流程体现在画布中（「给人一种很厉害的感觉」）。
实现为只读展示节点 `pipeline_stage`；随后按用户反馈把分镜收敛为**一个大节点列表形态**
（`STORYBOARD`，内部滚动、镜头行内状态/时长/摘要/已选片段悬停播放）。
链条恒定 4 阶段：`开始→画风→故事→分镜→成片`。
详见 `docs/design/2026-11-07-production-blueprint-canvas.md`。

## 改动

- 后端 `CreativeCanvasService`：白名单 + 蓝图节点校验 + 只允许执行连线（不进运行域）。
- 前端：`canvasGraph.js`（连线守卫）、`CanvasGraphNode.vue`（蓝图渲染分支）、
  `ProjectCanvasTab.vue`（buildProductionBlueprint/applyBlueprint/工具栏按钮/sessionStorage 交接）、
  `VideoAssemblyStage.vue`（成片阶段「在画布生成制作蓝图」按钮）。

## 验证

- `CreativeCanvasServiceTest` 21/21（蓝图链/分镜列表/喂生成节点拒绝/坏 ID 拒绝）；
  creative 回归 49/49；前端 build 通过。
- 本地浏览器实测（admin，项目「生成小猫睡觉」4 镜头）：成片阶段按钮 → 自动跳画布生成；
  大节点版再同步成功且旧单镜节点被替换，「已保存」确认后端校验通过。
  未调用任何生成模型（项目无候选，视频预览待用户真实生成后验收）。

## 省察修正（第二轮）

- 蓝图节点 footer 全部带「同步于 HH:mm」（快照诚实性）；连线标签改「编排顺序」并让
  `normalizeConnection` 保留已存标签；成片节点标题带「缺 N 镜」（按镜头无已选片段计算）；
  画风节点补画幅/目标时长；分镜行视频 `preload="none"`。均为前端改动，后端无变化。

## 统一存储：本地共用服务器 MinIO（2026-11-07 下午，用户拍板）

用户方向：服务器与本地必须用同一套存储（服务器 MinIO）。落地：

- 本地 SSH 隧道（`com.aster.ssh-tunnel` launchd 任务）新增转发 `127.0.0.1:19000 → 服务器 9000`。
- `application-local.yml`（git-ignored，增量修改经用户明确要求）新增 `app.storage.provider=s3`
  + `app.storage.s3.endpoint=http://127.0.0.1:19000`，凭据与服务器 `.env` 同款（不回显）。
  注意该文件打进 jar：改完必须 `mvn package` 再重启才生效。
- 端到端验证：本地后端上传测试素材（201）→ 读回 70 字节 → 服务器共享库该素材行
  `storage_backend=S3` → 测试素材已删除。本地零存量迁移负担（`~/.aster/creative-media` 为空）。
- 移除了另一会话遗留的 5 个未接线文件（`creative/storage/` 的 MediaStorage 抽象：
  OSS/本地目录双实现），与「统一 MinIO」方向冲突；现有 `platform/storage` 的
  ObjectStorage 链路（provider=s3 即 MinIO）就是正解，无需第二套抽象。
- **待用户操作**：阿里云安全组放行 **9443** 端口（MinIO 预签名公网入口
  `https://www.zhuwenxuan.online:9443`，nginx 已就绪但公网连不通）。放行后服务器
  `.env` 追加 `MINIO_PUBLIC_ENDPOINT=https://www.zhuwenxuan.online:9443` 并重启
  backend——在此之前，服务器端签发的素材 URL host 是 172.17.0.1:9000，公网浏览器
  打不开（本地开发经隧道不受影响）。

## OSS 退役清理（2026-11-07 续，用户确认「已不用阿里云存储」）

- 服务器是**腾讯云**（1.13.152.215）；此前误说阿里云。9443 放行 = 腾讯云控制台
  （轻量→防火墙 / CVM→安全组）加 TCP 9443 入站规则，与存储供应商无关。
- **阿里云 AccessKey 已被禁用**（迁移报 UserDisable）：36 个 OSS 时代素材因此已不可读
  （禁用后本来就读不出，撤兜底只是让失败更直接）。若那批旧图还需要，须在阿里云控制台
  恢复 AccessKey 后重跑一次迁移（`STORAGE_MIGRATE=true`，幂等只复制）。
- 28 个 LOCAL 标记素材：服务器与本地目录均为空，文件已不存在，属早期遗留，不可恢复。
- 服务器 `.env`：`STORAGE_FALLBACK_PROVIDER=`（已清空）、`STORAGE_MIGRATE=false`；
  compose 模板补了 `STORAGE_MIGRATE` 透传（d9f945f）。ALIYUN_OSS_* 键保留（知识库
  OssClientProvider 仍在引用，知识库本就搁置）。
- 本地 `application-local.yml`：`app.oss` 块已移除（用户明确不用阿里云存储），
  重打包重启后「主=S3 兜底=无」。本地与服务器现在都只读写服务器 MinIO。

## 9443 公网入口修复（2026-11-07 续）

- **事实更正**：创作素材播放/下载走**后端代理流**（`/content` 带令牌，后端从 MinIO 读后转发），
  不依赖 9443；`presignedGetUrl` 仅知识库图片存储在用。此前「不放行素材就打不开」说法有误。
- 排障链条：安全组（用户已在腾讯云放行）→ **nginx-proxy 容器从未发布 9443 端口**（手工
  `docker run` 容器，compose 里没有该服务）→ reload 信号未生效 → 容器 restart 后 nginx
  才真正监听 9443 → 重建容器补 `-p 9443:9443`。
- **事故记录**：重建时发现原容器镜像 tag `aster-frontend:0.1.0` 已丢失（容器以无 tag 镜像
  `d7796c95ec7c` 运行），`docker run` 按名找不到导致**站点中断约 1–2 分钟**；改用镜像 ID
  重建恢复。已打固定 tag `aster-nginx-proxy:base`。重建命令（务必留档）：
  `docker run -d --name aster-nginx-proxy --restart unless-stopped --network aster_default
   -p 80:80 -p 443:443 -p 9443:9443
   -v /opt/aster/nginx/certs:/etc/nginx/certs
   -v /opt/aster/nginx/nginx.conf:/etc/nginx/nginx.conf
   -v /var/www/certbot:/var/www/certbot aster-nginx-proxy:base`
- 服务器 `.env` 已设 `MINIO_PUBLIC_ENDPOINT=https://www.zhuwenxuan.online:9443`
  （compose 补了透传），本地 `application-local.yml` 同款 public-endpoint；公网
  `GET /minio/health/live` 实测 200。本地端到端（上传→读回→DB 标记 S3）此前已验。

## 遗留

- 用户今晚真实生成验收（Seedance 首条 ¥7–9 + 成片导出）后，蓝图里的镜头/成片节点
  会有真实视频可播放；重新点一次「制作蓝图」即可刷新快照。
- 已知既有失败与本轮无关：ToolGuardHookTest / MediaJobSchedulerTest /
  PgVectorVectorStoreConfigTest（DashScope 欠费）。

## 注意

- 蓝图节点 id 固定（bp-*），再同步按 id 替换并保留拖动位置；用户手动删除后同步即恢复。
- `sessionStorage('aster.creative.blueprint')` 是跨 tab 交接标记，画布 loadCanvas 消费后即清除。
- **服务器镜像清理教训（2026-11-07）**：手动 `docker rmi` 旧版本镜像时，必须保留
  `/opt/aster/.env` 的 `IMAGE_TAG` 所引用的 tag（compose 全部服务都用 `${IMAGE_TAG}`）。
  未变更服务不重建容器，运行镜像落后于 IMAGE_TAG（如 backend 跑 0.7.19、IMAGE_TAG=0.7.20），
  旧 tag 被删会让下次容器重建失败。恢复方式：`docker tag <运行中镜像> aster-xxx:${IMAGE_TAG}`
  （部署脚本对未变更服务本就如此重标签，内容等价）。
  另：browser-mcp / artifact-mcp 的 0.7.20 tag 缺失是历史遗留（两服务从未在该服务器启动，无影响）。
- 服务器 nginx 无 proxy_cache；`index.html` 已是 `Cache-Control: no-cache`，无需清 HTTP 缓存。
