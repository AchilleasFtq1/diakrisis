# syntax=docker/dockerfile:1
#
# etl (BerkaEtl seed CLI) — multi-stage build.
#
# Build context is the Maven reactor ROOT (see docker-compose.yml `build.context: .`). etl depends
# only on the `common` shared library, so the builder installs `common` to .m2 then shades etl into
# a self-contained runnable jar (finalName=etl).
#
# The Berka data is NOT copied into the image (it is ~86 MB); compose mounts it read-only at /data
# and BerkaEtl is invoked with `--berka-dir /data/berka`.
#
# Stage 1 (builder): maven:3.9-eclipse-temurin-26 — Maven 3.9 + Temurin JDK 26 (verified to exist).
# Stage 2 (runtime): eclipse-temurin:26-jre — slim JRE 26, no Maven/JDK toolchain.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-26 AS builder
WORKDIR /build

COPY common/pom.xml common/pom.xml
COPY etl/pom.xml    etl/pom.xml

COPY common/src common/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f common/pom.xml clean install -DskipTests

COPY etl/src etl/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f etl/pom.xml clean package -DskipTests

RUN cp etl/target/etl.jar /build/etl.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:26-jre AS runtime
WORKDIR /app

RUN useradd --system --create-home --uid 10001 appuser
USER appuser

COPY --from=builder /build/etl.jar /app/etl.jar

# Default args target the compose network; override `command:` in compose to add --demo etc.
ENTRYPOINT ["java", "-jar", "/app/etl.jar"]
CMD ["--berka-dir", "/data/berka", "--ddb-endpoint", "http://dynamodb:8000", "--demo"]
