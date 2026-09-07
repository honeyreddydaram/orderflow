# Build context is the repo root (needed for the Maven reactor: common + product-service).
# docker-compose.yml builds this with `context: .`, `dockerfile: product-service/Dockerfile`.

FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY common/src common/src
COPY product-service/src product-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl product-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S orderflow && adduser -S orderflow -G orderflow
COPY --from=build /workspace/product-service/target/product-service.jar app.jar
USER orderflow

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
