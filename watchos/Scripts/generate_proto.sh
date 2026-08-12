#!/bin/bash
# Генерирует Swift-классы protobuf из proto/bridge.proto.
# Требования: brew install protobuf swift-protobuf  (protoc + protoc-gen-swift)
set -euo pipefail

cd "$(dirname "$0")/.."

PROTO_DIR="../proto"
OUT_DIR="WristRelayWatchOS/Generated"

mkdir -p "$OUT_DIR"

if ! command -v protoc >/dev/null 2>&1; then
  echo "Ошибка: protoc не найден. Установите: brew install protobuf" >&2
  exit 1
fi
if ! command -v protoc-gen-swift >/dev/null 2>&1; then
  echo "Ошибка: protoc-gen-swift не найден. Установите: brew install swift-protobuf" >&2
  exit 1
fi

protoc --swift_out="$OUT_DIR" --proto_path="$PROTO_DIR" "$PROTO_DIR/bridge.proto"

echo "Сгенерировано в $OUT_DIR:"
ls -la "$OUT_DIR"
