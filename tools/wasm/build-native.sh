#!/usr/bin/env bash
# 编译 Wasm 插件引擎(Wasmtime JNI 桥),产物放进 app/src/main/jniLibs/arm64-v8a/libmika_wasm.so。
#
# 用法:tools/wasm/build-native.sh [--check]
#   --check  只重编并与仓库里的产物比较 SHA-256,不一致退出 1(不覆盖仓库文件)
#
# 需要 rustup(工具链版本由 tools/wasm/rust-toolchain.toml 固定)、ANDROID_NDK_HOME
# (或 ANDROID_HOME 下的 ndk/28.2.13676358)。
set -euo pipefail

NDK_VERSION=28.2.13676358
API=30

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WASM_DIR="$ROOT/tools/wasm"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libmika_wasm.so"
TARGET=aarch64-linux-android

CHECK=0
case "${1:-}" in
	"") ;;
	--check) CHECK=1 ;;
	*) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/$NDK_VERSION}"
case "$(uname -s)" in
	Darwin) HOST_TAG=darwin-x86_64; EXT="" ;;
	Linux) HOST_TAG=linux-x86_64; EXT="" ;;
	MINGW*|MSYS*|CYGWIN*) HOST_TAG=windows-x86_64; EXT=".cmd" ;;
	*) echo "unsupported host $(uname -s)" >&2; exit 2 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$TOOLCHAIN/aarch64-linux-android$API-clang$EXT"
STRIP="$TOOLCHAIN/llvm-strip"
[ -e "$CC" ] || { echo "NDK clang not found: $CC" >&2; exit 2; }
NDK_REVISION="$(awk -F' *= *' '$1=="Pkg.Revision"{print $2}' "$NDK/source.properties" 2>/dev/null || true)"
if [ "$NDK_REVISION" != "$NDK_VERSION" ]; then
	echo "NDK version mismatch: $NDK has '${NDK_REVISION:-unknown}', need $NDK_VERSION" >&2
	exit 2
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CC_aarch64_linux_android="$CC"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export RANLIB_aarch64_linux_android="$TOOLCHAIN/llvm-ranlib"
# 去掉构建路径,便于跨机器比较产物
export RUSTFLAGS="--remap-path-prefix=$WASM_DIR=. --remap-path-prefix=$HOME/.cargo=/.cargo"

cd "$WASM_DIR"
cargo build --release --target "$TARGET" --quiet
"$STRIP" --strip-all "target/$TARGET/release/libmika_wasm.so"

if [ "$CHECK" = 1 ]; then
	expected="$(sha256sum "$OUT" 2>/dev/null | cut -d' ' -f1 || sha256sum "target/$TARGET/release/libmika_wasm.so" | cut -d' ' -f1)"
	actual="$(sha256sum "target/$TARGET/release/libmika_wasm.so" | cut -d' ' -f1)"
	if [ "$expected" != "$actual" ]; then
		echo "libmika_wasm.so is stale: committed $expected, built $actual" >&2
		exit 1
	fi
	echo "libmika_wasm.so is up to date ($actual)"
else
	mkdir -p "$OUT_DIR"
	cp "target/$TARGET/release/libmika_wasm.so" "$OUT"
	echo "wrote $OUT"
fi
