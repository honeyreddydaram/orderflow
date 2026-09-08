# Build context is the repo root (needed for the Maven reactor: common + payment-service).
# docker-compose.yml builds this with `context: .`, `dockerfile: payment-service/Dockerfile`.

FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY common/src common/src
COPY payment-service/src payment-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl payment-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# curl is needed for docker-compose.yml's healthcheck (Alpine ships neither curl nor wget by default).
RUN apk add --no-cache curl && addgroup -S orderflow && adduser -S orderflow -G orderflow
COPY --from=build /workspace/payment-service/target/payment-service.jar app.jar
USER orderflow

EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
