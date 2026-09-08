# Build context is the repo root (needed for the Maven reactor: common + order-service).
# docker-compose.yml builds this with `context: .`, `dockerfile: order-service/Dockerfile`.

FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY common/src common/src
COPY order-service/src order-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl order-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# curl is needed for docker-compose.yml's healthcheck (Alpine ships neither curl nor wget by default).
RUN apk add --no-cache curl && addgroup -S orderflow && adduser -S orderflow -G orderflow
COPY --from=build /workspace/order-service/target/order-service.jar app.jar
USER orderflow

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
