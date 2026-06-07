#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/skydnir-native"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/root/android-ndk-r26d}}"
API="${ANDROID_API:-26}"

mkdir -p "$JNI"

pick_host_tag() {
    if [[ -n "${ANDROID_NDK_HOST_TAG:-}" ]]; then
        printf '%s\n' "$ANDROID_NDK_HOST_TAG"
        return
    fi
    if [[ -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
        printf 'linux-x86_64\n'
        return
    fi
    find "$NDK/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort | head -1
}

HOST_TAG="$(pick_host_tag)"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CLANG="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"
RESOURCE_DIR="$(find "$TOOLCHAIN/lib/clang" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"

COMMON_WARN=(-Wall -Wextra -Wno-unused-parameter -Wno-unused-function -U_FORTIFY_SOURCE)
ANDROID_EXEC_FLAGS=(-fPIE -pie -O2 "${COMMON_WARN[@]}")
GLIBC_CC="${SKYDNIR_GLIBC_CC:-${CC_ARM64:-aarch64-linux-gnu-gcc}}"
GLIBC_FLAGS=(-O2 -Wall -Wextra)

# Match the upstream Android native-helper build model: execute the build in a
# glibc host environment, but target Android/Bionic through the NDK sysroot,
# compiler-rt, and aarch64-linux-android target triple. Do not invoke device or
# Termux-local compilers for APK payload generation.
HOST_CLANG="${HOST_CLANG:-$(command -v clang || true)}"
[[ -n "$HOST_CLANG" && -x "$HOST_CLANG" ]] || { echo "ABORT: glibc-host clang was not found" >&2; exit 1; }
[[ -d "$SYSROOT/usr/include" ]] || { echo "ABORT: Android/Bionic payloads require the NDK sysroot: $SYSROOT" >&2; exit 1; }
[[ -n "$RESOURCE_DIR" && -d "$RESOURCE_DIR" ]] || { echo "ABORT: NDK clang resource dir missing under $TOOLCHAIN/lib/clang" >&2; exit 1; }
command -v ld.lld >/dev/null 2>&1 || { echo "ABORT: glibc-host Android build requires ld.lld" >&2; exit 1; }
ANDROID_CC=("$HOST_CLANG")
ANDROID_CC_EXTRA=(
    "--target=aarch64-linux-android${API}"
    "--sysroot=$SYSROOT"
    "-resource-dir=$RESOURCE_DIR"
    "-rtlib=compiler-rt"
    "-L$SYSROOT/usr/lib/aarch64-linux-android/$API"
)

strip_payload() {
    local path="$1"
    if [[ "${SKYDNIR_NATIVE_STRIP:-0}" != "0" && -x "$STRIP" ]]; then
        "$STRIP" --strip-unneeded "$path" || true
    fi
}

build_android_exec() {
    local out="$1"; shift
    echo "==> building $(basename "$out") from Skydnir source"
    "${ANDROID_CC[@]}" "${ANDROID_CC_EXTRA[@]}" "${ANDROID_EXEC_FLAGS[@]}" -o "$out" "$@"
    chmod 0755 "$out"
    strip_payload "$out"
}

build_glibc_exec() {
    local out="$1"; shift
    command -v "$GLIBC_CC" >/dev/null 2>&1 || {
        echo "ABORT: missing glibc cross compiler '$GLIBC_CC' for Skydnir container payloads" >&2
        exit 1
    }
    echo "==> building $(basename "$out") from Skydnir source"
    "$GLIBC_CC" "${GLIBC_FLAGS[@]}" -fPIE -pie -o "$out" "$@"
    chmod 0755 "$out"
}

build_glibc_shared() {
    local out="$1"; shift
    command -v "$GLIBC_CC" >/dev/null 2>&1 || {
        echo "ABORT: missing glibc cross compiler '$GLIBC_CC' for Skydnir container payloads" >&2
        exit 1
    }
    echo "==> building $(basename "$out") from Skydnir source"
    "$GLIBC_CC" "${GLIBC_FLAGS[@]}" -fPIC -shared -Wl,-Bsymbolic -o "$out" "$@"
    chmod 0755 "$out"
}

build_android_exec "$JNI/libskydnirdirect.so" "$SRC/cpp/skydnir_direct_exec.c"
build_android_exec "$JNI/libskydnirgpuexecutor.so" "$SRC/cpp/skydnir_gpu_executor.c" -lEGL -lGLESv3 -lvulkan -llog -ldl -lm
build_android_exec "$JNI/libskydnirmediaexecutor.so" "$SRC/cpp/skydnir_media_executor.c"

build_glibc_shared "$JNI/libcow.so" "$SRC/overlay/skydnir_cow.c" -ldl
build_glibc_exec "$JNI/libskydnirgpushim.so" "$SRC/gpu/skydnir_gpu_shim.c"
build_glibc_shared "$JNI/libskydnirvulkanicd.so" "$SRC/gpu/skydnir_vulkan_icd.c"
build_glibc_shared "$JNI/libskydniropenclicd.so" "$SRC/gpu/skydnir_opencl_icd.c"

if [[ "${SKYDNIR_FDROID_NO_CRANE:-0}" != "0" ]]; then
    rm -f "$JNI/libcrane.so"
    echo "F-Droid mode: omitted prebuilt crane payload"
else
    install -m 0755 "$SRC/crane" "$JNI/libcrane.so"
fi

if [[ -n "${SKYDNIR_GLIBC_LOADER:-}" ]]; then
    install -m 0755 "$SKYDNIR_GLIBC_LOADER" "$JNI/libpdocker-ld-linux-aarch64.so"
elif [[ ! -f "$JNI/libpdocker-ld-linux-aarch64.so" ]]; then
    echo "ABORT: set SKYDNIR_GLIBC_LOADER or keep $JNI/libpdocker-ld-linux-aarch64.so available" >&2
    exit 1
fi

echo "Skydnir native payloads are ready in $JNI"
