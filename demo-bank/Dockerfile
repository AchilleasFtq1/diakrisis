# syntax=docker/dockerfile:1
#
# demo-bank — multi-stage build.
#
# The demo-bank is a STANDALONE Spring Boot app OUTSIDE the diakritis reactor (its own parent POM).
# It depends on nothing from the product — it talks to Diakrisis only over HTTP — so its build
# context is this directory alone. It stores state in an ephemeral SQLite file that is dropped and
# re-seeded on every boot (SqliteSchemaInitializer), keeping demos deterministic.
#
# Stage 1 (builder): maven:3.9-eclipse-temurin-26 — Maven 3.9 + Temurin JDK 26.
# Stage 2 (runtime): eclipse-temurin:26-jre — slim JRE 26, no Maven/JDK toolchain.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-26 AS builder
WORKDIR /build

COPY pom.xml pom.xml
COPY src     src
RUN --mount=type=cache,target=/root/.m2 mvn -q clean package -DskipTests

RUN cp target/demo-bank-*.jar /build/app.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:26-jre AS runtime
WORKDIR /app

# curl is used by the compose healthcheck to probe the index page (demo-bank has no actuator).
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 appuser

# demo-bank owns its SQLite store: give it a writable data dir (the app runs as the non-root
# appuser, so /app and the db location must be writable by that user). The db is dropped and
# re-seeded on every boot, so it is ephemeral container-local state — it belongs to this service.
RUN mkdir -p /app/data && chown -R appuser:appuser /app
USER appuser

COPY --chown=appuser:appuser --from=builder /build/app.jar /app/app.jar

# The SQLite file lives under the service's own writable data dir (overridable via SPRING_DATASOURCE_URL).
ENV SPRING_DATASOURCE_URL=jdbc:sqlite:/app/data/demo-bank.db

EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
