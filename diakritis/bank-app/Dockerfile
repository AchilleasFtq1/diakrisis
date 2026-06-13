# syntax=docker/dockerfile:1
#
# bank-app — multi-stage build.
#
# Build context is the Maven reactor ROOT (see docker-compose.yml `build.context: .`). bank-app
# depends only on the `common` shared library (not engine), so the builder installs `common` to .m2
# then builds bank-app standalone.
#
# Stage 1 (builder): maven:3.9-eclipse-temurin-26 — Maven 3.9 + Temurin JDK 26 (verified to exist).
# Stage 2 (runtime): eclipse-temurin:26-jre — slim JRE 26, no Maven/JDK toolchain.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-26 AS builder
WORKDIR /build

COPY common/pom.xml   common/pom.xml
COPY bank-app/pom.xml bank-app/pom.xml

COPY common/src  common/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f common/pom.xml clean install -DskipTests

COPY bank-app/src bank-app/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f bank-app/pom.xml clean package -DskipTests

RUN cp bank-app/target/bank-app-*.jar /build/app.jar

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
