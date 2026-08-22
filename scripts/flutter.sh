#!/usr/bin/env bash
# Runs Flutter for the mobile app inside a container, so no local Flutter/Dart install is needed.
#
#   ./scripts/flutter.sh pub get
#   ./scripts/flutter.sh analyze
#   ./scripts/flutter.sh test
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLUTTER_IMAGE="${FLUTTER_IMAGE:-ghcr.io/cirruslabs/flutter:3.24.5}"

mkdir -p "${HOME}/.pub-cache"

exec docker run --rm \
  --network host \
  -v "${REPO_ROOT}/mobile":/app \
  -w /app \
  -v "${HOME}/.pub-cache":/root/.pub-cache \
  -e PUB_CACHE=/root/.pub-cache \
  "${FLUTTER_IMAGE}" \
  flutter "$@"
