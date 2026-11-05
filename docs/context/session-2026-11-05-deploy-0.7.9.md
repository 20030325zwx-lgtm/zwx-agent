# 部署 0.7.9（入口缓存策略清理）

## 背景

用户要求部署后清理缓存。检查结论：
- 域名直连源站 `1.13.152.215`，无 CDN；外层 `aster-nginx-proxy` 配置为 `proxy_cache off`，无服务端缓存；Docker 无代理缓存目录占用。
- 唯一缺口：`index.html`（含 SPA 回退路由）没有显式缓存头，浏览器会启发式缓存，部署后可能继续引用旧入口/旧资源。

## 改动（提交 7a1d 系列）

- `frontend/nginx.conf`：
  - `location /` 增加 `Cache-Control: no-cache`（覆盖 SPA 入口与回退路由）；
  - 新增 `location = /index.html` 同样 no-cache；
  - hashed 静态资源保持 `expires 1y + public` 不变。

## 部署与验证

- 版本 0.7.9：前端重建发布，后端沿用 0.7.8（未重启）；双层 nginx 已重载。
- 线上校验：
  - `/` 与 `/login` → `cache-control: no-cache`；
  - `assets/index-*.js|css` → `cache-control: max-age=31536000, public`；
  - 主资产哈希与本机 dist 一致；公网 health ok。

## 注意

- 已存在的浏览器缓存无法远程清除：首次访问可能仍命中旧入口，强制刷新（⌘⇧R）一次后，后续部署都会自动拿到新入口。
- 以后发布无需手动清缓存：入口 no-cache + 资源哈希双重保证。
