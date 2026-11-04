# 数据中台独立服务镜像：前端构建 → Maven 打包 → JRE 运行
FROM node:20-alpine AS frontend
WORKDIR /ui
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline -q || true
COPY src ./src
# 前端产物由 Vite 以 base=/data/ 输出到 static/data
COPY --from=frontend /ui/dist ./src/main/resources/static/data
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /src/target/aster-data-service-0.0.1-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8141
HEALTHCHECK --interval=15s --timeout=5s --retries=5 CMD wget -qO- http://127.0.0.1:8141/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
