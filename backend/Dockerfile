# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN chmod +x mvnw

RUN --mount=type=cache,id=flowai-backend-maven,target=/root/.m2 \
    ./mvnw -B -DskipTests dependency:go-offline

COPY src/ src/

RUN --mount=type=cache,id=flowai-backend-maven,target=/root/.m2 \
    ./mvnw -B -DskipTests clean package \
    && cp target/backend-*.jar target/app.jar


FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache curl \
    && addgroup -S flowai \
    && adduser -S flowai -G flowai

WORKDIR /app

COPY --from=build \
    --chown=flowai:flowai \
    /workspace/target/app.jar \
    /app/app.jar

USER flowai

EXPOSE 8080

HEALTHCHECK --interval=15s \
    --timeout=3s \
    --start-period=30s \
    --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
