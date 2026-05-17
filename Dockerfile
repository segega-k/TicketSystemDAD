FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# OpenTelemetry Java agent — auto-instrumentation (Spec §12.1)
ARG OTEL_AGENT_VERSION=2.7.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
RUN chown app:app /app/opentelemetry-javaagent.jar && chmod 0644 /app/opentelemetry-javaagent.jar

COPY --from=build --chown=app:app /workspace/target/ticket-system-dad-*.jar /app/app.jar

USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME="tickets-backend" \
    OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf" \
    OTEL_METRICS_EXPORTER="none" \
    OTEL_LOGS_EXPORTER="otlp" \
    OTEL_TRACES_EXPORTER="otlp"

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:${PORT:-8080}/actuator/health/readiness || curl -fsS http://127.0.0.1:${PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
