# syntax=docker/dockerfile:1
# Build context is the repository root:  docker build -f services/notification-svc/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY rules/xom rules/xom
COPY contracts contracts
COPY services services
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl services/notification-svc -am -DskipTests -Dspotless.check.skip=true package

FROM eclipse-temurin:21-jre-jammy
# curl for the compose healthcheck; the app itself needs nothing else
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -m 755 /otel && mkdir /app && chgrp 0 /app && chmod g=u /app
# OpenTelemetry Java agent 2.17.0 (traces over OTLP; checksum pinned). Kafka, HTTP and JDBC clients are instrumented automatically.
ADD --chmod=644 --checksum=sha256:48570b90ab967db579e535c5edda52bea077c2e0b9d92874acd73bf9b69de50b https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.17.0/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build --chown=10001:0 /src/services/notification-svc/target/notification-svc-*-exec.jar /app/app.jar
# Non-root, and any UID works (OpenShift assigns a random one in group 0)
USER 10001
WORKDIR /app
ENV OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
