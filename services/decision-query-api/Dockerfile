# syntax=docker/dockerfile:1
# Build context is the repository root:  docker build -f services/decision-query-api/Dockerfile .
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
# Maven Central answers 429/502/503 now and then; retry instead of failing the image build
ENV MAVEN_ARGS="-Daether.connector.http.retryHandler.count=5 -Daether.connector.http.retryHandler.interval=5000 -Daether.connector.http.retryHandler.serviceUnavailable=429,500,502,503,504"
COPY pom.xml ./
COPY rules/xom rules/xom
COPY contracts contracts
COPY services services
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl services/decision-query-api -am -DskipTests -Dspotless.check.skip=true package

FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0
# curl for the compose healthcheck; the app itself needs nothing else
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -m 755 /otel && mkdir /app && chgrp 0 /app && chmod g=u /app
# OpenTelemetry Java agent 2.26.1 (traces over OTLP; checksum pinned). Kafka, HTTP and JDBC clients are instrumented automatically.
ADD --chmod=644 --checksum=sha256:cc4af5966ab72109cacc962ba3b9f99b3e88caf064c3144a451bcfe0f4950f19 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build --chown=10001:0 /src/services/decision-query-api/target/decision-query-api-*-exec.jar /app/app.jar
# Non-root, and any UID works (OpenShift assigns a random one in group 0)
USER 10001
WORKDIR /app
ENV OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
