#!/usr/bin/env bash
#
# Builds the Hiroi debug APK inside podman, so the Android SDK never touches
# the host system. See Containerfile.android.
#
# Usage:
#   podman-build-apk.sh [--install]
#     --install  also `adb install -r` the APK (needs adb on the host)
#
# Output: build/outputs/hiroi-debug.apk

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

INSTALL=false
case "${1:-}" in
  "") ;;
  --install) INSTALL=true ;;
  *) echo "usage: $0 [--install]" >&2; exit 1 ;;
esac

if ! command -v podman >/dev/null 2>&1; then
  echo "error: podman is not installed or not on PATH" >&2
  exit 1
fi

OUT_DIR="$REPO_ROOT/build/outputs"
mkdir -p "$OUT_DIR"

echo "==> Building debug APK inside podman (toolchain layers are cached after the first run)"
# --ulimit nofile: RUN steps get a low open-files limit by default, which is
# too little for Gradle ("Too many open files" while configuring the build).
podman build \
  --ulimit nofile=65536:65536 \
  -f Containerfile.android \
  --target out \
  -o type=local,dest="$OUT_DIR" \
  .

APK_PATH="$OUT_DIR/hiroi-debug.apk"
if [ ! -f "$APK_PATH" ]; then
  echo "==> Build finished but APK wasn't found at $APK_PATH" >&2
  exit 1
fi
echo "==> APK ready: $APK_PATH"

if $INSTALL; then
  adb install -r "$APK_PATH"
fi
