# syntax=docker/dockerfile:1
#
# decision-service — multi-stage build.
#
# Build context is the Maven reactor ROOT (see docker-compose.yml `build.context: .`), because the
# standalone per-service build still needs the two shared libraries (common, engine) installed to
# the builder's local .m2 first.
#
# Stage 1 (builder): maven:3.9-eclipse-temurin-26 — Maven 3.9 + Temurin JDK 26 (verified to exist).
# Stage 2 (runtime): eclipse-temurin:26-jre — slim JRE 26, no Maven/JDK toolchain.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-26 AS builder
WORKDIR /build

# Copy only the poms first so Maven can resolve+cache dependencies in a layer that is invalidated
# only when a pom changes (not on every source edit).
COPY common/pom.xml          common/pom.xml
COPY engine/pom.xml          engine/pom.xml
COPY decision-service/pom.xml decision-service/pom.xml

# Install the shared libraries to the builder's .m2 (offline-friendly dependency warming first).
COPY common/src  common/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f common/pom.xml clean install -DskipTests

COPY engine/src  engine/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f engine/pom.xml clean install -DskipTests

# Now build decision-service standalone (resolves common/engine from the warmed .m2).
COPY decision-service/src decision-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f decision-service/pom.xml clean package -DskipTests

# Normalise the fat jar name so the runtime stage copies a stable path.
RUN cp decision-service/target/decision-service-*.jar /build/app.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:26-jre AS runtime
WORKDIR /app

# curl is used by the compose healthcheck to probe /actuator/health.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN useradd --system --create-home --uid 10001 appuser
USER appuser

COPY --from=builder /build/app.jar /app/app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
