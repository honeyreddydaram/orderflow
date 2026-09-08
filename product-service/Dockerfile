# Build context is the repo root (needed for the Maven reactor: common + product-service).
# docker-compose.yml builds this with `context: .`, `dockerfile: product-service/Dockerfile`.

FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /workspace

COPY pom.xml .
# The root pom.xml's <modules> lists every service - Maven's reactor parse requires all of them
# to exist on disk even though `-pl product-service -am` below only ever builds this one, so
# every sibling's pom.xml (not its src) has to be copied in too.
COPY common/pom.xml common/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY common/src common/src
COPY product-service/src product-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl product-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# curl is needed for docker-compose.yml's healthcheck (Alpine ships neither curl nor wget by default).
RUN apk add --no-cache curl && addgroup -S orderflow && adduser -S orderflow -G orderflow
COPY --from=build /workspace/product-service/target/product-service.jar app.jar
USER orderflow

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
