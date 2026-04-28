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
    NDK_PATH=$(ls -d $HOME/Android/Sdk/ndk/* 2>/dev/null | sort -V | tail -1)
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
declare -A ARCH_MAP=( ["arm64-v8a"]="arm64;aarch64-linux-android" ["armeabi-v7a"]="arm;armv7a-linux-androideabi" ["x86_64"]="amd64;x86_64-linux-android" )

needs_rebuild() {
    [ ! -f "$2" ] && return 0
    [ -n "$(find "$1" -maxdepth 5 -name "*.go" -newer "$2" -print -quit)" ] && return 0
    return 1
}

# Compile SIGSYS→ENOSYS shim for ARM.
# Android ARM seccomp blocks clone3 (syscall 435), sending SIGSYS instead of ENOSYS.
# This constructor intercepts SIGSYS and returns -ENOSYS so the Go runtime falls back
# to the old clone syscall, fixing crash with exit code 159.
compile_arm_sigsys_fix() {
    local src="/tmp/_go_arm_sigsys_fix.c"
    local obj="/tmp/_go_arm_sigsys_fix.o"
    local arc="/tmp/_go_arm_sigsys_fix.a"
    cat > "$src" << 'EOF'
#ifdef __arm__
#include <signal.h>
#include <string.h>
#include <sys/ucontext.h>
static struct sigaction _prev;
static void _handler(int s, siginfo_t *i, void *c) {
    if (i->si_code == 1 /* SYS_SECCOMP */) {
        ((ucontext_t *)c)->uc_mcontext.arm_r0 = (unsigned long)(-38); /* -ENOSYS */
        return;
    }
    if (_prev.sa_flags & SA_SIGINFO) _prev.sa_sigaction(s, i, c);
}
__attribute__((constructor(101))) static void _install(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = _handler;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSYS, &sa, &_prev);
}
#endif
EOF
    "$TOOLCHAIN/armv7a-linux-androideabi30-clang" \
        -target armv7a-linux-androideabi30 -fPIC -c "$src" -o "$obj" 2>/dev/null && \
    ar rcs "$arc" "$obj" && echo "$arc"
}
ARM_SIGSYS_FIX=$(compile_arm_sigsys_fix)
[ -z "$ARM_SIGSYS_FIX" ] && echo "WARNING: Failed to compile ARM SIGSYS fix (clone3 patch skipped)"

build_go_project() {
    local dir=$1; local out_name=$2; local sub_pkg=$3
    echo "Checking $out_name..."
    cd "$ROOT_DIR/$dir"

    local pids=()
    for abi in arm64-v8a armeabi-v7a x86_64; do
        (
            IFS=';' read -r goarch target <<< "${ARCH_MAP[$abi]}"
            OUT="$JNI_LIBS_DIR/$abi/$out_name"

            # Add GOARM for ARMv7 + SIGSYS fix for clone3 (exit code 159 on ARM)
            if [ "$goarch" == "arm" ]; then
                export GOARM=7
                ARM_EXTRA=""
                [ -n "$ARM_SIGSYS_FIX" ] && ARM_EXTRA="-Wl,--whole-archive $ARM_SIGSYS_FIX -Wl,--no-whole-archive"
            else
                unset GOARM
                ARM_EXTRA=""
            fi

            if needs_rebuild "." "$OUT"; then
                echo "  → Building $abi..."
                mkdir -p "$(dirname "$OUT")"
                CGO_ENABLED=1 GOOS=android GOARCH=$goarch CC="$TOOLCHAIN/${target}30-clang" \
                CGO_CFLAGS="-target ${target}30 -fPIC" \
                CGO_LDFLAGS="-target ${target}30 -Wl,--no-undefined -Wl,-z,max-page-size=16384 $ARM_EXTRA" \
                go build -trimpath -ldflags="-s -w -checklinkname=0" -o "$OUT" "$sub_pkg"
            fi
        ) &
        pids+=($!)
    done

    # Wait for all ABIs of this project to finish
    for pid in "${pids[@]}"; do
        wait "$pid" || exit 1
    done
}

# Ensure submodules exist
[ ! -f "external/tun2socks/go.mod" ] && git submodule update --init --recursive

# Run builds
build_go_project "external/tun2socks"    "libtun2socks.so" "."
build_go_project "external/vk-turn-proxy" "libvkturn.so"    "./client"
build_go_project "external/vless-client"  "libxray.so"     "."
build_go_project "external/turnable"      "libturnable.so" "./cmd"

chmod +x "$JNI_LIBS_DIR"/*/*.so 2>/dev/null || true
echo "Build finished."
