# Engiens backend: production image. Base images are pinned by multi-architecture digest (x86 and ARM64/Ampere).
#
# Build:  docker build -t engiens-backend:<git-sha> backend      (on EC2, deploy/docker-compose.prod.yml builds it)
# Runs with the prod profile, as a non-root user, on port 8080. The Docker CLI is included because the backend starts
# the Scenario Lab sandbox containers through the host's Docker socket (mounted by docker-compose.prod.yml).

# ---- build: compile and package the jar (tests run in CI, not in the image build) ----
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/backend-*.jar /app.jar

# ---- docker CLI: a static binary, copied into the runtime image (no daemon, no other tooling) ----
FROM docker:27-cli@sha256:851f91d241214e7c6db86513b270d58776379aacc5eb9c4a87e5b47115e3065c AS docker-cli

# ---- runtime: Java 21 JRE only ----
FROM eclipse-temurin:21-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
RUN groupadd --system --gid 10001 engiens && useradd --system --uid 10001 --gid engiens --no-create-home engiens
WORKDIR /app
COPY --from=build /app.jar app.jar
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
USER engiens
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
