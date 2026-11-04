FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# 运行镜像需要 headless LibreOffice（格式转换）与 CJK 字体；
# 只做 docx/pptx/pdf 渲染、不需要转换时可去掉 libreoffice-*（镜像可省 ~600MB）。
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libreoffice-writer libreoffice-calc libreoffice-impress \
        fonts-noto-cjk fontconfig curl \
    && fc-cache -f >/dev/null 2>&1 \
    && rm -rf /var/lib/apt/lists/*
ENV ARTIFACT_PDF_FONT_PATH=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc
COPY --from=build /build/target/aster-artifact-mcp-server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8130
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
