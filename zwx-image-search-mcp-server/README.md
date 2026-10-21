# zwx-image-search-mcp-server

独立的 SSE MCP 服务，提供：

- `searchImage(query)`：按关键词搜索图片；
- `describe_image(image_url, question)`：图片摘要、相关文字与检索 query；
- `ocr_image(image_url)`：完整 OCR；

## 本地运行

```bash
mvn -DskipTests package
DASHSCOPE_API_KEY=... java -jar target/zwx-image-search-mcp-server-0.0.1-SNAPSHOT.jar
```

默认端口 `8127`，SSE 地址 `http://127.0.0.1:8127/sse`，健康检查 `GET /health`。

## Docker 运行

```bash
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY
docker compose up -d --build
```

服务只绑定 `127.0.0.1:8127`。如果主应用也在 Docker 中，可通过：

- `http://host.docker.internal:8127`（Docker Desktop / OrbStack），或
- 与主应用加入同一个 Docker network 后使用容器名。

```bash
docker compose ps
docker compose logs -f
```

如果本机 Docker registry mirror 拉取 `docker.io` 失败，可先使用可用的镜像源再打回标准 tag，例如：

```bash
docker pull docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21
docker pull docker.m.daocloud.io/library/eclipse-temurin:21-jre-alpine
docker tag docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 maven:3.9-eclipse-temurin-21
docker tag docker.m.daocloud.io/library/eclipse-temurin:21-jre-alpine eclipse-temurin:21-jre-alpine
```

## 在主应用配置

在 MCP 管理页面添加 SSE 服务：

```text
名称：图片理解
地址：http://127.0.0.1:8127
```

主应用会通过 `tools/call` 调用 `describe_image`，传入短时 OSS 签名 URL 和用户问题。

## 安全

- 服务本身没有鉴权，默认只绑定 localhost；不要直接暴露公网。
- `IMAGE_ALLOWED_HOSTS` 限制工具接受的图片 URL host，默认只允许阿里云 OSS、本机地址。
- `DASHSCOPE_API_KEY` 只通过环境变量注入，不写入镜像或仓库。
- 跨主机部署时应加访问控制、TLS 和网络隔离。
