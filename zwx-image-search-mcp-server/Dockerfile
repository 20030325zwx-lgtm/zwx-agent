# syntax=docker/dockerfile:1.6
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/target/zwx-image-search-mcp-server-0.0.1-SNAPSHOT.jar /app/mcp-server.jar
USER app
EXPOSE 8127
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8127/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/mcp-server.jar"]
