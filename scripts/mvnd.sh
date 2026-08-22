#!/usr/bin/env bash
# Runs Maven for the backend inside a container, so no local JDK/Maven install is needed.
# The host ~/.m2 is mounted, so the dependency cache is shared with any local Maven you do have.
#
#   ./scripts/mvnd.sh compile
#   ./scripts/mvnd.sh -DskipTests package
#   ./scripts/mvnd.sh test
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9-eclipse-temurin-21}"

mkdir -p "${HOME}/.m2"

# --network host so the integration tests can reach the Postgres and Redis published by
# `docker compose up -d postgres redis` on localhost.
exec docker run --rm \
  --network host \
  -v "${REPO_ROOT}/backend":/app \
  -w /app \
  -v "${HOME}/.m2":/root/.m2 \
  -e MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1" \
  "${MAVEN_IMAGE}" \
  mvn -B -ntp "$@"
