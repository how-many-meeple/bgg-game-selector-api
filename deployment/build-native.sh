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
zip -j "$SCRIPT_DIR/bgg-api-native.zip" "$SCRIPT_DIR/bootstrap"
rm "$SCRIPT_DIR/bootstrap"

echo "Done: $SCRIPT_DIR/bgg-api-native.zip"
echo "Deploy with: sam deploy --template-file serverless-template.yaml"
