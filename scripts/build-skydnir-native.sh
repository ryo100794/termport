#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/skydnir-native"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/root/android-ndk-r26d}}"
API="${ANDROID_API:-26}"
if [[ -n "${ANDROID_ABI:-}" ]]; then
    ABIS=("$ANDROID_ABI")
else
    # shellcheck disable=SC2206
    ABIS=(${ANDROID_ABIS:-arm64-v8a armeabi-v7a})
fi

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

android_target_triple() {
    case "$1" in
        arm64-v8a) printf 'aarch64-linux-android' ;;
        armeabi-v7a) printf 'armv7a-linux-androideabi' ;;
        *) echo "ABORT: unsupported Android ABI '$1'" >&2; return 2 ;;
    esac
}

android_sysroot_lib_triple() {
    case "$1" in
        arm64-v8a) printf 'aarch64-linux-android' ;;
        armeabi-v7a) printf 'arm-linux-androideabi' ;;
        *) echo "ABORT: unsupported Android ABI '$1'" >&2; return 2 ;;
    esac
}

glibc_cc_for_abi() {
    case "$1" in
        arm64-v8a) printf '%s\n' "${SKYDNIR_GLIBC_CC:-${CC_ARM64:-aarch64-linux-gnu-gcc}}" ;;
        armeabi-v7a) printf '%s\n' "${SKYDNIR_GLIBC_CC_ARMHF:-${CC_ARMHF:-arm-linux-gnueabihf-gcc}}" ;;
        *) echo "ABORT: unsupported glibc ABI '$1'" >&2; return 2 ;;
    esac
}

glibc_loader_for_abi() {
    case "$1" in
        arm64-v8a) printf '%s\n' "${SKYDNIR_GLIBC_LOADER:-/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1}" ;;
        armeabi-v7a) printf '%s\n' "${SKYDNIR_GLIBC_LOADER_ARMHF:-/usr/arm-linux-gnueabihf/lib/ld-linux-armhf.so.3}" ;;
        *) return 2 ;;
    esac
}

loader_output_for_abi() {
    case "$1" in
        arm64-v8a) printf 'libpdocker-ld-linux-aarch64.so' ;;
        armeabi-v7a) printf 'libskydnir-ld-linux-armhf.so' ;;
        *) return 2 ;;
    esac
}

direct_source_for_abi() {
    case "$1" in
        arm64-v8a) printf '%s\n' "$SRC/cpp/skydnir_direct_exec.c" ;;
        armeabi-v7a) printf '%s\n' "$SRC/cpp/skydnir_direct_unsupported.c" ;;
        *) return 2 ;;
    esac
}

HOST_TAG="$(pick_host_tag)"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"
RESOURCE_DIR="$(find "$TOOLCHAIN/lib/clang" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"

COMMON_WARN=(-Wall -Wextra -Wno-unused-parameter -Wno-unused-function -U_FORTIFY_SOURCE)
ANDROID_EXEC_FLAGS=(-fPIE -pie -O2 "${COMMON_WARN[@]}")
GLIBC_FLAGS=(-O2 -Wall -Wextra)

# Match the upstream Android native-helper build model: execute the build in a
# glibc host environment, but target Android/Bionic through the NDK sysroot,
# compiler-rt, and Android target triples. Do not invoke device or Termux-local
# compilers for APK payload generation.
HOST_CLANG="${HOST_CLANG:-$(command -v clang || true)}"
[[ -n "$HOST_CLANG" && -x "$HOST_CLANG" ]] || { echo "ABORT: glibc-host clang was not found" >&2; exit 1; }
[[ -d "$SYSROOT/usr/include" ]] || { echo "ABORT: Android/Bionic payloads require the NDK sysroot: $SYSROOT" >&2; exit 1; }
[[ -n "$RESOURCE_DIR" && -d "$RESOURCE_DIR" ]] || { echo "ABORT: NDK clang resource dir missing under $TOOLCHAIN/lib/clang" >&2; exit 1; }
command -v ld.lld >/dev/null 2>&1 || { echo "ABORT: glibc-host Android build requires ld.lld" >&2; exit 1; }

setup_android_cc() {
    local target="$1" lib_triple="$2" ndk_clang
    local -n out_cmd="$3"
    local -n out_extra="$4"
    ndk_clang="$TOOLCHAIN/bin/${target}${API}-clang"
    if [[ -x "$ndk_clang" ]] && "$ndk_clang" --version >/dev/null 2>&1; then
        out_cmd=("$ndk_clang")
        out_extra=()
        return
    fi
    out_cmd=("$HOST_CLANG")
    out_extra=(
        "--target=${target}${API}"
        "--sysroot=$SYSROOT"
        "-resource-dir=$RESOURCE_DIR"
        "-rtlib=compiler-rt"
        "-L$SYSROOT/usr/lib/$lib_triple/$API"
    )
}

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
    local glibc_cc="$1"; shift
    command -v "$glibc_cc" >/dev/null 2>&1 || {
        echo "ABORT: missing glibc cross compiler '$glibc_cc' for Skydnir container payloads" >&2
        exit 1
    }
    echo "==> building $(basename "$out") from Skydnir source"
    "$glibc_cc" "${GLIBC_FLAGS[@]}" -fPIE -pie -o "$out" "$@"
    chmod 0755 "$out"
}

build_glibc_shared() {
    local out="$1"; shift
    local glibc_cc="$1"; shift
    command -v "$glibc_cc" >/dev/null 2>&1 || {
        echo "ABORT: missing glibc cross compiler '$glibc_cc' for Skydnir container payloads" >&2
        exit 1
    }
    echo "==> building $(basename "$out") from Skydnir source"
    "$glibc_cc" "${GLIBC_FLAGS[@]}" -fPIC -shared -Wl,-Bsymbolic -o "$out" "$@"
    chmod 0755 "$out"
}

echo "==> NDK: $NDK"
echo "==> host-tag: $HOST_TAG API: android$API ABIs: ${ABIS[*]}"

for ABI in "${ABIS[@]}"; do
    JNI="$ROOT/app/src/main/jniLibs/$ABI"
    TARGET_TRIPLE="$(android_target_triple "$ABI")"
    LIB_TRIPLE="$(android_sysroot_lib_triple "$ABI")"
    GLIBC_CC="$(glibc_cc_for_abi "$ABI")"
    LOADER_SRC="$(glibc_loader_for_abi "$ABI")"
    LOADER_OUT="$(loader_output_for_abi "$ABI")"
    ANDROID_CC=()
    ANDROID_CC_EXTRA=()

    mkdir -p "$JNI"
    setup_android_cc "$TARGET_TRIPLE" "$LIB_TRIPLE" ANDROID_CC ANDROID_CC_EXTRA

    echo "==> ABI: $ABI"
    build_android_exec "$JNI/libskydnirdirect.so" "$(direct_source_for_abi "$ABI")"
    build_android_exec "$JNI/libskydnirgpuexecutor.so" "$SRC/cpp/skydnir_gpu_executor.c" -lEGL -lGLESv3 -lvulkan -llog -ldl -lm
    build_android_exec "$JNI/libskydnirmediaexecutor.so" "$SRC/cpp/skydnir_media_executor.c"

    build_glibc_shared "$JNI/libcow.so" "$GLIBC_CC" "$SRC/overlay/skydnir_cow.c" -ldl
    build_glibc_exec "$JNI/libskydnirgpushim.so" "$GLIBC_CC" "$SRC/gpu/skydnir_gpu_shim.c"
    build_glibc_shared "$JNI/libskydnirvulkanicd.so" "$GLIBC_CC" "$SRC/gpu/skydnir_vulkan_icd.c"
    build_glibc_shared "$JNI/libskydniropenclicd.so" "$GLIBC_CC" "$SRC/gpu/skydnir_opencl_icd.c"

    if [[ "$ABI" == "arm64-v8a" && "${SKYDNIR_FDROID_NO_CRANE:-0}" == "0" ]]; then
        install -m 0755 "$SRC/crane" "$JNI/libcrane.so"
    else
        rm -f "$JNI/libcrane.so"
        echo "omitted crane payload for $ABI"
    fi

    if [[ -f "$LOADER_SRC" ]]; then
        install -m 0755 "$LOADER_SRC" "$JNI/$LOADER_OUT"
    elif [[ ! -f "$JNI/$LOADER_OUT" ]]; then
        echo "ABORT: missing glibc loader for $ABI: $LOADER_SRC" >&2
        exit 1
    fi

    echo "Skydnir native payloads are ready in $JNI"
done
