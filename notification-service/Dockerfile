# Build context is the repo root (needed for the Maven reactor: common + notification-service).
# docker-compose.yml builds this with `context: .`, `dockerfile: notification-service/Dockerfile`.

FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY common/src common/src
COPY notification-service/src notification-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl notification-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# curl is needed for docker-compose.yml's healthcheck (Alpine ships neither curl nor wget by default).
RUN apk add --no-cache curl && addgroup -S orderflow && adduser -S orderflow -G orderflow
COPY --from=build /workspace/notification-service/target/notification-service.jar app.jar
USER orderflow

EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
