#!/usr/bin/env bash
# 编译 ABI v1 的测试 guest(wasm32-unknown-unknown),用于引擎/ABI 验证。
# 用法:tools/wasm/build-test-guest.sh [输出路径]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUEST="$ROOT/tools/wasm/test-guest"
OUT="${1:-$GUEST/target/mika_test_guest.wasm}"

cd "$GUEST"
rustup target add wasm32-unknown-unknown
cargo build --release --target wasm32-unknown-unknown --quiet
cp "target/wasm32-unknown-unknown/release/mika_test_guest.wasm" "$OUT"
echo "wrote $OUT"
