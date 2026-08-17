FROM eclipse-temurin:21-jre

WORKDIR /app

ARG JAR_FILE=target/zwx-agent-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

RUN mkdir -p /app/temp

EXPOSE 8123

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
