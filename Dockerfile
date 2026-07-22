# syntax=docker/dockerfile:1

###############################################################################
# Stage 1 — build the executable jar with the Temurin 21 JDK
###############################################################################
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# Copy only the Maven wrapper + POM first so dependency resolution is cached in
# its own layer and re-runs only when the POM actually changes.
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/

# A Windows checkout can give mvnw CRLF line endings, which makes the Linux
# shell reject it with "bad interpreter". Strip CRs so the build is portable.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
 && ./mvnw -B -ntp dependency:go-offline

# Now bring in the sources and build. Tests are skipped here on purpose: the
# context-load test needs a live PostgreSQL, so it belongs in CI, not in the
# image build (which must be hermetic and DB-free).
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests clean package

###############################################################################
# Stage 2 — minimal runtime on the Temurin 21 JRE
###############################################################################
FROM eclipse-temurin:21-jre AS runtime

# curl is needed for the container HEALTHCHECK; the JRE base image has no HTTP
# client of its own. Keep the layer small and drop the apt cache afterwards.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged, system user — never root inside the container.
RUN groupadd --system --gid 1001 spring \
 && useradd --system --uid 1001 --gid spring --home-dir /app --shell /usr/sbin/nologin spring

WORKDIR /app

# Copy the repackaged jar (version-agnostic glob) and hand ownership to the
# non-root user. Only the artifact crosses stages — no build tooling or sources.
COPY --from=build --chown=spring:spring /build/target/ecommerce-api-*.jar app.jar

# Default log dir logback-spring.xml falls back to when LOG_PATH isn't set
# (e.g. orchestrators that build this image without mounting a log volume).
# Must be writable by the non-root runtime user, not just root-owned WORKDIR.
RUN mkdir -p /app/logs && chown -R spring:spring /app/logs

USER spring

EXPOSE 8080

# Container-friendly JVM defaults; override at runtime via -e JAVA_OPTS=...
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# Fail the health check if the actuator endpoint is not reporting healthy.
# --start-period covers Spring Boot + DB connection warm-up so early boot
# doesn't count as failures. A DOWN status returns 503, so curl -f fails.
HEALTHCHECK --start-period=40s --interval=30s --timeout=5s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# exec form so the JVM is PID 1 and receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
