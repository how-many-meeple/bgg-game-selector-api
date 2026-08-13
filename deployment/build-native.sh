#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Building native image for Lambda (provided.al2023)..."

docker build \
  -f "$SCRIPT_DIR/Dockerfile.native" \
  -t bgg-api-native-build \
  "$PROJECT_DIR"

CONTAINER_ID=$(docker create bgg-api-native-build)
docker cp "$CONTAINER_ID:/var/runtime/bootstrap" "$SCRIPT_DIR/bootstrap"
docker rm "$CONTAINER_ID"

chmod +x "$SCRIPT_DIR/bootstrap"

rm -f "$SCRIPT_DIR/bgg-api-native.zip"
if command -v zip >/dev/null 2>&1; then
  zip -j "$SCRIPT_DIR/bgg-api-native.zip" "$SCRIPT_DIR/bootstrap"
elif command -v powershell >/dev/null 2>&1; then
  # Windows/Git Bash fallback: no `zip` on PATH. PowerShell needs Windows-style paths,
  # so translate the POSIX paths (cygpath ships with Git Bash).
  win_bootstrap="$(cygpath -w "$SCRIPT_DIR/bootstrap")"
  win_zip="$(cygpath -w "$SCRIPT_DIR/bgg-api-native.zip")"
  powershell -Command "Compress-Archive -Path '$win_bootstrap' -DestinationPath '$win_zip' -Force"
else
  echo "Error: neither 'zip' nor 'powershell' found on PATH; cannot package bootstrap." >&2
  exit 1
fi
rm "$SCRIPT_DIR/bootstrap"

echo "Done: $SCRIPT_DIR/bgg-api-native.zip"
echo "Deploy with: sam deploy --template-file serverless-template.yaml"
