#!/bin/bash
set -e

# 1. Fix line endings if script is corrupted by Windows CRLF
if grep -q $'\r' "$0"; then
    sed -i 's/\r$//' "$0"
    exec bash "$0" "$@"
fi

ROOT_DIR=$(pwd)
JNI_LIBS_DIR="$ROOT_DIR/app/src/main/jniLibs"

# Select targets: go | cmake | rust | all (default). Determined early so section 3 below
# can skip the (Go-only) patched-GOROOT setup for a cmake-only invocation.
TARGET="${1:-all}"

# 2. Setup NDK Path: the version the app declares (android.ndkVersion - NDK_VERSION from Gradle,
# else gradle.properties), not whichever is newest, so a local build matches CI's. Only with no
# version to go by does the newest installed one do.
if [ -z "$NDK_PATH" ]; then
    [ -z "$NDK_VERSION" ] && NDK_VERSION=$(grep -oP '(?<=android\.ndkVersion=)\S+' "$ROOT_DIR/gradle.properties" 2>/dev/null)
    if [ -n "$NDK_VERSION" ]; then
        for sdk in "$HOME/Android/Sdk" "$ANDROID_HOME"; do
            if [ -n "$sdk" ] && [ -d "$sdk/ndk/$NDK_VERSION" ]; then
                NDK_PATH="$sdk/ndk/$NDK_VERSION"
                break
            fi
        done
        if [ -z "$NDK_PATH" ]; then
            echo "ERROR: NDK $NDK_VERSION not found in \$HOME/Android/Sdk/ndk or \$ANDROID_HOME/ndk."
            echo "  Install it: sdkmanager \"ndk;$NDK_VERSION\" (or set NDK_PATH)"
            exit 1
        fi
    else
        NDK_PATH=$(ls -d "$HOME/Android/Sdk/ndk"/* 2>/dev/null | sort -V | tail -1)
        if [ -z "$NDK_PATH" ] && [ -n "$ANDROID_HOME" ]; then
            NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -1)
        fi
    fi
fi

if [ -z "$NDK_PATH" ] || [ ! -d "$NDK_PATH" ]; then
    echo "ERROR: NDK not found. Set NDK_PATH or install it via SDK Manager."
    exit 1
fi
echo "Using NDK: $NDK_PATH"

# The local up-to-date checks below only compare source mtimes, so binaries built by another NDK
# would stay. On a change, all of them go at once - also the other targets', whose next run then
# finds them missing and rebuilds. CI checks submodule hashes and keys its cache by NDK instead.
NDK_STAMP="$JNI_LIBS_DIR/.ndk"
if [ "$CI" != "true" ] && [ "$(cat "$NDK_STAMP" 2>/dev/null)" != "$(basename "$NDK_PATH")" ]; then
    if compgen -G "$JNI_LIBS_DIR/*/*.so" > /dev/null; then
        echo "NDK changed ($(cat "$NDK_STAMP" 2>/dev/null || echo unknown) -> $(basename "$NDK_PATH")): rebuilding all native binaries"
        rm -f "$JNI_LIBS_DIR"/*/*.so
    fi
    # ndk-build's objects too - it would relink hev from them, not recompile.
    rm -rf "$ROOT_DIR/external/hev-socks5-tunnel/obj" "$ROOT_DIR/external/hev-socks5-tunnel/libs"
    mkdir -p "$JNI_LIBS_DIR"
    basename "$NDK_PATH" > "$NDK_STAMP"
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

# 3b. Setup Rust (rustup's cargo lives in ~/.cargo/bin, which a plain `bash -l` from Gradle
# doesn't always have on PATH - its env script is only sourced from interactive shells).
if [ "$TARGET" = "all" ] || [ "$TARGET" = "rust" ]; then
    if ! command -v cargo &> /dev/null && [ -f "$HOME/.cargo/env" ]; then
        . "$HOME/.cargo/env"
    fi
    if ! command -v cargo &> /dev/null || ! cargo ndk --version &> /dev/null; then
        echo "ERROR: cargo / cargo-ndk not found. Install rustup, then:"
        echo "  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android"
        echo "  cargo install cargo-ndk --locked"
        exit 1
    fi
    export ANDROID_NDK_HOME="$NDK_PATH"
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

# CI only: records which submodule commit an ABI's cached .so was built from. One file per ABI -
# build_go_project checks all ABIs in parallel, and a single shared file let the first ABI to get
# there write the new hash and every other ABI see "up to date", keeping its stale .so from an
# older actions/cache entry (restored via restore-keys whenever the submodules changed).
ci_hash_file() { echo "$1/.git_hash_$(basename "$(dirname "$2")")"; }

# $3: source language of the project ("go" by default, or "rust"), for the local mtime check.
needs_rebuild() {
    [ ! -f "$2" ] && return 0

    # В CI проверяем по хэшу коммита сабмодуля (записывается после успешной сборки)
    if [ "$CI" = "true" ]; then
        local current_hash=$(git -C "$1" rev-parse HEAD 2>/dev/null)
        [ "$(cat "$(ci_hash_file "$1" "$2")" 2>/dev/null)" = "$current_hash" ] && return 1
        return 0
    fi

    local -a sources=(-name "*.go" -o -name "go.mod" -o -name "go.sum")
    [ "$3" = "rust" ] && sources=(-name "*.rs" -o -name "Cargo.toml" -o -name "Cargo.lock")
    [ -n "$(find "$1" -maxdepth 5 \( "${sources[@]}" \) -newer "$2" -print -quit)" ] && return 0
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

    # Fetch what the build needs once, before the parallel ABI builds below: each of them would
    # otherwise resolve and download the same modules (and the GOTOOLCHAIN switch) on its own at
    # the same time - four "go: downloading" lines per module in the log. `go list -deps` only
    # loads packages, no compiling, so any one GOARCH does; the rare module another ABI needs on
    # top still gets fetched by that build. Skipped when every ABI is already up to date.
    local abi
    for abi in $ALL_ABIS; do
        if needs_rebuild "." "$JNI_LIBS_DIR/$abi/$out_name"; then
            GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -deps "$sub_pkg" > /dev/null
            break
        fi
    done

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
                # A plain `[ ... ] && ...` would leave this subshell's exit status at 1 outside CI.
                if [ "$CI" = "true" ]; then git rev-parse HEAD > "$(ci_hash_file "." "$OUT")"; fi
            fi
        ) &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do
        wait "$pid" || exit 1
    done
}

# Rust binaries via cargo-ndk. No 32-bit x86 - the same three ABIs CSQTT upstream builds and ships
# (its app's abiFilters); an i686 build was never tried. On x86 devices the app doesn't offer CSQTT for new
# profiles, and an imported one fails to start with an "unavailable for this architecture" error.
RUST_ABIS="arm64-v8a armeabi-v7a x86_64"
declare -A RUST_TRIPLE=(
    ["arm64-v8a"]="aarch64-linux-android"
    ["armeabi-v7a"]="armv7-linux-androideabi"
    ["x86_64"]="x86_64-linux-android"
)

# Cargo's target dir. A checkout on a Windows drive built from WSL (/mnt/...) keeps it on WSL's own
# filesystem instead: across the 9P mount LTO builds take several times longer. CI caches
# build/rust-target, so it stays there otherwise. RUST_TARGET_DIR overrides both.
if [ -n "$RUST_TARGET_DIR" ]; then
    RUST_TARGET_ROOT="$RUST_TARGET_DIR"
elif [[ "$ROOT_DIR" == /mnt/* ]]; then
    RUST_TARGET_ROOT="$HOME/.cache/wireturn/rust-target"
else
    RUST_TARGET_ROOT="$ROOT_DIR/build/rust-target"
fi

build_rust_project() {
    local dir=$1; local bin_name=$2; local out_name=$3
    echo "Checking $out_name..."
    cd "$ROOT_DIR/$dir"
    # Outside the submodule, so its checkout stays clean; one directory for every ABI, so the
    # host-side build scripts/proc-macros are compiled once - which also means the ABIs build one
    # after another (cargo locks the target dir), unlike the parallel Go builds.
    local target_dir="$RUST_TARGET_ROOT/${out_name%.so}"

    for abi in $RUST_ABIS; do
        local out="$JNI_LIBS_DIR/$abi/$out_name"
        needs_rebuild "." "$out" rust || continue
        # CSQTT's vendored LuaJIT builds its code generator with a 32-bit host compiler for a
        # 32-bit target - without gcc-multilib that fails deep in mlua-sys's build script.
        if [ "$abi" = "armeabi-v7a" ] && ! echo 'int main(void){return 0;}' | cc -m32 -x c - -o /dev/null 2>/dev/null; then
            echo "ERROR: building $out_name for $abi needs a 32-bit host C compiler (cc -m32)."
            echo "  Install it: sudo apt-get install gcc-multilib"
            exit 1
        fi
        echo "  → Building $abi..."
        mkdir -p "$(dirname "$out")"
        # Same 16 KB page alignment as the Go builds' -z max-page-size (Android 15+ devices).
        # --link-builtins: compiler-rt's builtins, which rustc's -nodefaultlibs link leaves out -
        # CSQTT's vendored LuaJIT (Gooby) calls __clear_cache from there; upstream builds the same.
        RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
        cargo ndk -t "$abi" -P "$MIN_SDK" --link-builtins build --release --locked --target-dir "$target_dir"
        cp "$target_dir/${RUST_TRIPLE[$abi]}/release/$bin_name" "$out"
        if [ "$CI" = "true" ]; then git rev-parse HEAD > "$(ci_hash_file "." "$out")"; fi
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

# ByeDPI (ciadpi): plain C, no dependencies - its Makefile only lists the sources, and running it
# per ABI would leave .o files in the shared source tree. One NDK clang call per ABI instead,
# built as a PIE executable named lib*.so so the package manager extracts it like the others.
build_byedpi() {
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
            [ "$(cat "$(ci_hash_file "$ROOT_DIR/$dir" "$out")" 2>/dev/null)" != "$(git rev-parse HEAD 2>/dev/null)" ] && needs_build=1 && break
        else
            [ -n "$(find . -maxdepth 1 \( -name '*.c' -o -name '*.h' \) -newer "$out" -print -quit)" ] && needs_build=1 && break
        fi
    done
    [ "$needs_build" = "0" ] && return 0

    local sources="packets.c main.c conev.c proxy.c desync.c mpool.c extend.c"
    for abi in $ALL_ABIS; do
        IFS=';' read -r _ target _ <<< "${ARCH_MAP[$abi]}"
        local out="$JNI_LIBS_DIR/$abi/$out_name"
        mkdir -p "$JNI_LIBS_DIR/$abi"
        echo "  → $abi"
        "$TOOLCHAIN/${target}${MIN_SDK}-clang" \
            -D_DEFAULT_SOURCE -I. -std=c99 -O2 -fPIE -pie -s \
            -Wall -Wno-unused -Wextra -Wno-unused-parameter \
            $sources -o "$out"
        [ "$CI" = "true" ] && git rev-parse HEAD > "$(ci_hash_file "$ROOT_DIR/$dir" "$out")"
    done
    cd "$ROOT_DIR"
}

# 5. Build the selected targets (TARGET was resolved near the top of the script)
git submodule sync || true

if [ "$TARGET" = "all" ] || [ "$TARGET" = "cmake" ]; then
    [ ! -f "external/hev-socks5-tunnel/Android.mk" ] && git submodule update --init --recursive external/hev-socks5-tunnel
    build_hev_tunnel "external/hev-socks5-tunnel" "libhevsocks5.so"
    # build_hev_tunnel ends inside its submodule's own repository.
    cd "$ROOT_DIR"
    git submodule update --init --force external/byedpi
    build_byedpi "external/byedpi" "libbyedpi.so"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "go" ]; then
    git submodule update --init --recursive --force external/olcrtc
    git submodule update --init --recursive --force external/vless-client
    git submodule update --init --recursive --force external/turnable
    git submodule update --init --recursive --force external/webdav-tunnel
    git submodule update --init --recursive --force external/free-turn-proxy
    git submodule update --init --recursive --force external/proxy-turn-vk-android
    git submodule update --init --recursive --force external/openflux
    git submodule update --init --recursive --force external/socks2tun
    build_go_project "external/olcrtc"       "libolcrtc.so"     "./cmd/olcrtc"
    build_go_project "external/vless-client"  "libxray.so"      "."
    build_go_project "external/turnable"      "libturnable.so"  "./cmd"
    build_go_project "external/webdav-tunnel" "libwebdav.so"    "."
    build_go_project "external/free-turn-proxy" "libfreeturn.so" "./cmd/client"
    build_go_project "external/proxy-turn-vk-android/go_client" "libqwdtt.so" "."
    build_go_project "external/openflux"      "libopenflux.so"  "."
    build_go_project "external/socks2tun"     "libsocks2tun.so" "."
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "rust" ]; then
    git submodule update --init --recursive --force external/csqtt
    build_rust_project "external/csqtt/rust-client" "client" "libcsqtt.so"
fi

chmod +x "$JNI_LIBS_DIR"/*/*.so 2>/dev/null || true
echo "Build finished ($TARGET)."
