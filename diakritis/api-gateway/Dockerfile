# syntax=docker/dockerfile:1
#
# api-gateway — multi-stage build.
#
# Build context is the Maven reactor ROOT (see docker-compose.yml `build.context: .`). The gateway
# is fully self-contained: it depends on NOTHING from the reactor (no `common`, no `engine`) — only
# Spring Cloud Gateway Server MVC, jjwt, and springdoc, pinned to Spring Boot 4.0.x independently of
# the 4.1.0 backends it talks to over HTTP. So the builder just builds api-gateway standalone.
#
# Stage 1 (builder): maven:3.9-eclipse-temurin-26 — Maven 3.9 + Temurin JDK 26.
# Stage 2 (runtime): eclipse-temurin:26-jre — slim JRE 26, no Maven/JDK toolchain.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-26 AS builder
WORKDIR /build

COPY api-gateway/pom.xml api-gateway/pom.xml
COPY api-gateway/src     api-gateway/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f api-gateway/pom.xml clean package -DskipTests

RUN cp api-gateway/target/api-gateway-*.jar /build/app.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:26-jre AS runtime
WORKDIR /app

# curl is used by the compose healthcheck to probe /actuator/health.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 appuser
USER appuser

COPY --from=builder /build/app.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
