#!/bin/bash
set -e

# 1. Fix line endings if script is corrupted by Windows CRLF
if grep -q $'\r' "$0"; then
    sed -i 's/\r$//' "$0"
    exec bash "$0" "$@"
fi

ROOT_DIR=$(pwd)
JNI_LIBS_DIR="$ROOT_DIR/app/src/main/jniLibs"

# 2. Setup NDK Path
if [ -z "$NDK_PATH" ]; then
    NDK_PATH=$(ls -d "$HOME/Android/Sdk/ndk"/* 2>/dev/null | sort -V | tail -1)
fi
if [ -z "$NDK_PATH" ] && [ -n "$ANDROID_HOME" ]; then
    NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -1)
fi

if [ -z "$NDK_PATH" ] || [ ! -d "$NDK_PATH" ]; then
    echo "ERROR: NDK not found. Set NDK_PATH or install it via SDK Manager."
    exit 1
fi

TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin"

# 3. Setup Go
if ! command -v go &> /dev/null; then
    export PATH="/usr/local/go/bin:$HOME/go/bin:$PATH"
fi

# 4. Build Logic
declare -A ARCH_MAP=( ["arm64-v8a"]="arm64;aarch64-linux-android" ["x86_64"]="amd64;x86_64-linux-android" )

needs_rebuild() {
    [ ! -f "$2" ] && return 0
    [ -n "$(find "$1" -maxdepth 5 -name "*.go" -newer "$2" -print -quit)" ] && return 0
    return 1
}

build_go_project() {
    local dir=$1; local out_name=$2; local sub_pkg=$3
    echo "Checking $out_name..."
    cd "$ROOT_DIR/$dir"

    local pids=()
    for abi in arm64-v8a x86_64; do
        (
            IFS=';' read -r goarch target <<< "${ARCH_MAP[$abi]}"
            OUT="$JNI_LIBS_DIR/$abi/$out_name"

            if needs_rebuild "." "$OUT"; then
                echo "  → Building $abi..."
                mkdir -p "$(dirname "$OUT")"
                CGO_ENABLED=1 GOOS=android GOARCH=$goarch CC="$TOOLCHAIN/${target}30-clang" \
                CGO_CFLAGS="-target ${target}30 -fPIC" \
                CGO_LDFLAGS="-target ${target}30 -Wl,--no-undefined -Wl,-z,max-page-size=16384" \
                go build -trimpath -ldflags="-s -w -checklinkname=0" -o "$OUT" "$sub_pkg"
            fi
        ) &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do
        wait "$pid" || exit 1
    done
}

build_hev_tunnel() {
    local dir=$1; local out_name=$2
    echo "Checking $out_name..."
    cd "$ROOT_DIR/$dir"

    local needs_build=0
    for abi in arm64-v8a x86_64; do
        [ ! -f "$JNI_LIBS_DIR/$abi/$out_name" ] && needs_build=1 && break
        [ -n "$(find src third-part -maxdepth 6 -name "*.c" -newer "$JNI_LIBS_DIR/$abi/$out_name" -print -quit 2>/dev/null)" ] && needs_build=1 && break
    done
    [ "$needs_build" = "0" ] && return 0

    echo "  → Building with ndk-build..."
    "$NDK_PATH/ndk-build" \
        NDK_PROJECT_PATH=. \
        APP_BUILD_SCRIPT=Android.mk \
        NDK_APPLICATION_MK=Application.mk \
        APP_ABI="arm64-v8a x86_64" \
        APP_CFLAGS="-O3 -DPKGNAME=com/wireturn/app -DCLSNAME=HevSocks5Tunnel" \
        -j$(nproc 2>/dev/null || echo 4)

    for abi in arm64-v8a x86_64; do
        mkdir -p "$JNI_LIBS_DIR/$abi"
        cp "libs/$abi/libhev-socks5-tunnel.so" "$JNI_LIBS_DIR/$abi/$out_name"
    done
}

# 5. Select targets: go | cmake | all (default)
TARGET="${1:-all}"

if [ "$TARGET" = "all" ] || [ "$TARGET" = "cmake" ]; then
    [ ! -f "external/hev-socks5-tunnel/Android.mk" ] && git submodule update --init external/hev-socks5-tunnel
    build_hev_tunnel "external/hev-socks5-tunnel" "libhevsocks5.so"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "go" ]; then
    [ ! -f "external/vk-turn-proxy/go.mod" ]  && git submodule update --init external/vk-turn-proxy
    [ ! -f "external/vless-client/go.mod" ]   && git submodule update --init external/vless-client
    [ ! -f "external/turnable/go.mod" ]        && git submodule update --init external/turnable
    build_go_project "external/vk-turn-proxy" "libvkturn.so"    "./client"
    build_go_project "external/vless-client"  "libxray.so"      "."
    build_go_project "external/turnable"      "libturnable.so"  "./cmd"
fi

chmod +x "$JNI_LIBS_DIR"/*/*.so 2>/dev/null || true
echo "Build finished ($TARGET)."
