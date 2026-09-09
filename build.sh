#!/bin/bash
set -e

# 1. Fix line endings if script is corrupted by Windows CRLF
if grep -q $'\r' "$0"; then
    sed -i 's/\r$//' "$0"
    exec bash "$0" "$@"
fi

ROOT_DIR=$(pwd)
JNI_LIBS_DIR="$ROOT_DIR/app/src/main/jniLibs"

# Select targets: go | cmake | all (default). Determined early so section 3 below
# can skip the (Go-only) patched-GOROOT setup for a cmake-only invocation.
TARGET="${1:-all}"

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

# Native libs must target the same API level as project.minSdk, otherwise they
# link against symbols (e.g. android_get_device_api_level) that don't exist in
# libc.so on older devices and fail with "CANNOT LINK EXECUTABLE" at runtime.
MIN_SDK=$(grep -oP '(?<=project\.minSdk=)\d+' "$ROOT_DIR/gradle.properties" 2>/dev/null)
[ -z "$MIN_SDK" ] && MIN_SDK=26

# 3. Setup Go (skipped for a cmake-only invocation - it never touches Go)
if [ "$TARGET" = "all" ] || [ "$TARGET" = "go" ]; then

if ! command -v go &> /dev/null; then
    export PATH="/usr/local/go/bin:$HOME/go/bin:$PATH"
fi

# Go's runtime unconditionally used the futex_time64 syscall on 32-bit Linux targets
# (armeabi-v7a/x86) from ~Go 1.23 through Go 1.26.2, probing for kernel support via a
# call that expects -ENOSYS on failure. Android's app-domain seccomp filter on API<31
# doesn't allowlist that syscall and, unlike a real kernel, SIGSYS-kills the process
# outright instead of returning -ENOSYS - so the probe's intended fallback to the
# legacy futex syscall never runs, and every 32-bit Go binary launched by the app (not
# by `adb shell run-as`, which uses a different, more permissive seccomp domain)
# crashes instantly on startup regardless of kernel/config.
# Fixed upstream in Go 1.26.3 (golang.org/issue/78936, golang.org/issue/77930), which
# instead parses the running kernel's version via uname and only attempts
# futex_time64 on kernels that actually support it (Linux 5.1+) - explicitly written
# with this exact Android seccomp behavior in mind. The "+auto" suffix sets this as a
# floor: every module still gets at least its own go.mod's required version (or
# higher, e.g. free-turn-proxy's 1.26.7), just never anything older than the fix.
export GOTOOLCHAIN=go1.26.3+auto

fi

# 4. Build Logic
# Format per entry: goarch;clang target triple;GOARM (only set for armeabi-v7a, ignored otherwise)
declare -A ARCH_MAP=(
    ["arm64-v8a"]="arm64;aarch64-linux-android;"
    ["x86_64"]="amd64;x86_64-linux-android;"
    ["armeabi-v7a"]="arm;armv7a-linux-androideabi;7"
    ["x86"]="386;i686-linux-android;"
)
ALL_ABIS="arm64-v8a x86_64 armeabi-v7a x86"

needs_rebuild() {
    [ ! -f "$2" ] && return 0

    # В CI проверяем по хэшу коммита сабмодуля
    if [ "$CI" = "true" ]; then
        local submodule_hash_file="$1/.git_hash"
        local current_hash=$(git -C "$1" rev-parse HEAD 2>/dev/null)
        if [ -f "$submodule_hash_file" ] && [ "$(cat "$submodule_hash_file")" = "$current_hash" ]; then
            return 1
        else
            echo "$current_hash" > "$submodule_hash_file"
            return 0
        fi
    fi

    [ -n "$(find "$1" -maxdepth 5 \( -name "*.go" -o -name "go.mod" -o -name "go.sum" \) -newer "$2" -print -quit)" ] && return 0
    return 1
}

build_go_project() {
    local dir=$1; local out_name=$2; local sub_pkg=$3
    echo "Checking $out_name..."
    cd "$ROOT_DIR/$dir"

    # Most submodules commit go.sum; proxy-turn-vk-android/go_client deliberately gitignores it
    # upstream, so it's missing right after checkout - regenerate once before the parallel ABI
    # builds below race on writing it. `go mod tidy` (not `download`) because `download` alone
    # only fetches the module graph's roots, not every package actually imported by the source -
    # it silently leaves go.sum incomplete for this module's deep transitive import list.
    [ ! -f go.sum ] && go mod tidy

    local pids=()
    for abi in $ALL_ABIS; do
        (
            IFS=';' read -r goarch target goarm <<< "${ARCH_MAP[$abi]}"
            OUT="$JNI_LIBS_DIR/$abi/$out_name"

            if needs_rebuild "." "$OUT"; then
                echo "  → Building $abi..."
                mkdir -p "$(dirname "$OUT")"
                # 4-way parallel NDK clang/go invocations occasionally hit a transient
                # "No such file or directory" on the CC wrapper under WSL2 even though it
                # exists (fork/exec contention under load) - retry a few times before
                # giving up, rather than failing the whole build over a one-off hiccup.
                # GOARM must be a real (un)set, not an inline ${goarm:+...} assignment word -
                # bash's assignment-prefix parsing only recognizes literal "name=value" tokens
                # written directly in the source; a parameter expansion in that position isn't
                # lexically an assignment even when it expands to one, so it silently breaks the
                # rest of the prefix list and the next word gets executed as the command instead.
                if [ -n "$goarm" ]; then export GOARM=$goarm; else unset GOARM; fi
                for attempt in 1 2 3; do
                    CGO_ENABLED=1 GOOS=android GOARCH=$goarch CC="$TOOLCHAIN/${target}${MIN_SDK}-clang" \
                    CGO_CFLAGS="-target ${target}${MIN_SDK} -fPIC" \
                    CGO_LDFLAGS="-target ${target}${MIN_SDK} -Wl,--no-undefined -Wl,-z,max-page-size=16384" \
                    go build -trimpath -ldflags="-s -w -checklinkname=0" -o "$OUT" "$sub_pkg" && break
                    [ "$attempt" = 3 ] && exit 1
                    echo "  ⚠ $abi build attempt $attempt failed, retrying..."
                    sleep 2
                done
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
    for abi in $ALL_ABIS; do
        local out="$JNI_LIBS_DIR/$abi/$out_name"
        if [ ! -f "$out" ]; then
            needs_build=1
            break
        fi
        if [ "$CI" = "true" ]; then
            # actions/cache falls back to an older cache entry (restore-keys prefix
            # match) on a key miss, which can restore a stale .so that still passes
            # the plain existence check above. Same git-hash guard as needs_rebuild()
            # so a submodule bump always forces a rebuild in CI regardless of cache.
            local submodule_hash_file=".git_hash"
            local current_hash=$(git rev-parse HEAD 2>/dev/null)
            if [ ! -f "$submodule_hash_file" ] || [ "$(cat "$submodule_hash_file")" != "$current_hash" ]; then
                echo "$current_hash" > "$submodule_hash_file"
                needs_build=1
                break
            fi
        else
            [ -n "$(find src third-part -maxdepth 6 -type f -newer "$out" -print -quit 2>/dev/null)" ] && needs_build=1 && break
        fi
    done
    [ "$needs_build" = "0" ] && return 0

    echo "  → Building with ndk-build..."
    "$NDK_PATH/ndk-build" \
        NDK_PROJECT_PATH=. \
        APP_BUILD_SCRIPT=Android.mk \
        NDK_APPLICATION_MK=Application.mk \
        APP_ABI="$ALL_ABIS" \
        APP_CFLAGS="-O3 -DPKGNAME=com/wireturn/app -DCLSNAME=HevSocks5Tunnel" \
        -j$(nproc 2>/dev/null || echo 4)

    for abi in $ALL_ABIS; do
        mkdir -p "$JNI_LIBS_DIR/$abi"
        cp "libs/$abi/libhev-socks5-tunnel.so" "$JNI_LIBS_DIR/$abi/$out_name"
    done
}

# 5. Build the selected targets (TARGET was resolved near the top of the script)
git submodule sync || true

if [ "$TARGET" = "all" ] || [ "$TARGET" = "cmake" ]; then
    [ ! -f "external/hev-socks5-tunnel/Android.mk" ] && git submodule update --init --recursive external/hev-socks5-tunnel
    build_hev_tunnel "external/hev-socks5-tunnel" "libhevsocks5.so"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "go" ]; then
    git submodule update --init --recursive --force external/olcrtc
    git submodule update --init --recursive --force external/vless-client
    git submodule update --init --recursive --force external/turnable
    git submodule update --init --recursive --force external/webdav-tunnel
    git submodule update --init --recursive --force external/free-turn-proxy
    git submodule update --init --recursive --force external/proxy-turn-vk-android
    build_go_project "external/olcrtc"       "libolcrtc.so"     "./cmd/olcrtc"
    build_go_project "external/vless-client"  "libxray.so"      "."
    build_go_project "external/turnable"      "libturnable.so"  "./cmd"
    build_go_project "external/webdav-tunnel" "libwebdav.so"    "."
    build_go_project "external/free-turn-proxy" "libfreeturn.so" "./cmd/client"
    build_go_project "external/proxy-turn-vk-android/go_client" "libqwdtt.so" "."
fi

chmod +x "$JNI_LIBS_DIR"/*/*.so 2>/dev/null || true
echo "Build finished ($TARGET)."
