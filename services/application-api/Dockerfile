# syntax=docker/dockerfile:1
# Build context is the repository root:  docker build -f services/application-api/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY rules/xom rules/xom
COPY contracts contracts
COPY services services
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -pl services/application-api -am -DskipTests -Dspotless.check.skip=true package

FROM eclipse-temurin:21-jre-jammy
# curl for the compose healthcheck; the app itself needs nothing else
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir /app && chgrp 0 /app && chmod g=u /app
COPY --from=build --chown=10001:0 /src/services/application-api/target/application-api-*-exec.jar /app/app.jar
# Non-root, and any UID works (OpenShift assigns a random one in group 0)
USER 10001
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
