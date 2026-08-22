# Spring Boot API image.
#
# Multi-stage: the build stage carries the JDK, Maven, and the whole dependency cache (~700 MB);
# the runtime stage carries a JRE and the application layers (~250 MB). Nothing that was needed to
# compile ships to production, which is both smaller and a smaller attack surface.

# ---------------------------------------------------------------- dependencies --
FROM maven:3.9-eclipse-temurin-21 AS deps

WORKDIR /build

# The POM is copied alone first. Docker caches this layer on the POM's checksum, so editing Java
# source does not re-download every dependency — the difference between a 15-second rebuild and a
# five-minute one.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

# ---------------------------------------------------------------------- build --
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests clean package

# Spring Boot's layered jar splits into dependencies, spring-boot-loader, snapshot dependencies, and
# application classes. Extracting them into separate image layers means a code-only change ships a
# few hundred kilobytes instead of the entire fat jar.
RUN java -Djarmode=layertools -jar target/eshop-backend.jar extract --destination extracted

# -------------------------------------------------------------------- runtime --
FROM eclipse-temurin:21-jre-jammy AS runtime

# Runs as an unprivileged user. A container that does not need root should not have it — and on GKE
# this is what lets the pod satisfy `runAsNonRoot: true`.
RUN groupadd --system --gid 1001 eshop \
    && useradd --system --uid 1001 --gid eshop --home /app eshop \
    && mkdir -p /app /tmp/eshop-media \
    && chown -R eshop:eshop /app /tmp/eshop-media

WORKDIR /app

# Least-changing layers first, so the ones above stay cached across ordinary deploys.
COPY --from=build --chown=eshop:eshop /build/extracted/dependencies/ ./
COPY --from=build --chown=eshop:eshop /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=eshop:eshop /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=eshop:eshop /build/extracted/application/ ./

USER eshop

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="\
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom \
    -Duser.timezone=UTC \
    -Dfile.encoding=UTF-8"

# Notes on those flags:
#   MaxRAMPercentage  — the JVM reads the cgroup limit, so the heap tracks the pod's memory limit
#                       rather than the node's. A fixed -Xmx is wrong on every node size but one.
#   ExitOnOutOfMemory — a JVM that has exhausted its heap is not going to recover; exiting lets
#                       Kubernetes restart it instead of leaving a pod that answers health checks
#                       while failing every request.
#   urandom           — avoids the startup stall on /dev/random in a container with little entropy.

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
