FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/aster-document-mcp-server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8128
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
