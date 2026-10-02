#!/usr/bin/env bash
# Shared environment for the native dependency builds.
#
# Override anything by creating scripts/candy.env next to this file, or by
# exporting the variables before calling a build script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ---------------------------------------------------------------------------
# Optional local overrides (never committed).
# ---------------------------------------------------------------------------
if [[ -f "${SCRIPT_DIR}/candy.env" ]]; then
    # shellcheck disable=SC1091
    source "${SCRIPT_DIR}/candy.env"
fi

# ---------------------------------------------------------------------------
# Android SDK / NDK
# ---------------------------------------------------------------------------
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
ANDROID_API="${ANDROID_API:-26}"

if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}"
    elif [[ -n "${ANDROID_HOME:-}" ]]; then
        ANDROID_NDK_ROOT="${ANDROID_HOME}/ndk/${NDK_VERSION}"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        ANDROID_NDK_ROOT="${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}"
    else
        echo "error: set ANDROID_NDK_ROOT (or ANDROID_HOME/ANDROID_SDK_ROOT)" >&2
        exit 1
    fi
fi
export ANDROID_NDK_ROOT

# CMSIS-free builds still need the SDK's cmake/ninja when we build Poco.
if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
    ANDROID_HOME="${ANDROID_SDK_ROOT}"
fi
export ANDROID_HOME="${ANDROID_HOME:-}"

# ---------------------------------------------------------------------------
# Sources + output locations
# ---------------------------------------------------------------------------
THIRD_PARTY_DIR="${THIRD_PARTY_DIR:-${PROJECT_ROOT}/deps-src}"
DEPS_DIR="${DEPS_DIR:-${PROJECT_ROOT}/native-deps}"

OPENSSL_SRC="${OPENSSL_SRC:-${THIRD_PARTY_DIR}/openssl}"
OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
POCO_SRC="${POCO_SRC:-${THIRD_PARTY_DIR}/poco}"
POCO_VERSION="${POCO_VERSION:-1.13.3}"

JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
is_msys() {
    case "$(uname -s 2>/dev/null || echo unknown)" in
        MSYS*|MINGW*|CYGWIN*) return 0 ;;
        *) return 1 ;;
    esac
}

# OpenSSL's Android detection compares the path returned by `which clang`
# against $ANDROID_NDK_ROOT, so under MSYS both have to be in POSIX form.
to_posix_path() {
    local p="$1"
    if is_msys && command -v cygpath >/dev/null 2>&1; then
        cygpath -u "$p"
    else
        printf '%s' "$p"
    fi
}

# A path usable by native Windows tools (clang.exe, cmake.exe) launched from
# this shell.
to_windows_path() {
    local p="$1"
    if is_msys && command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$p"
    else
        printf '%s' "$p"
    fi
}

# Forward-slash native form ("C:/x/y"). OpenSSL's Configure runs under a
# native-Windows Perl, which cannot resolve MSYS "/c/..." paths, so the NDK
# root and any --prefix handed to Configure must use this form.
to_native_forward_path() {
    to_windows_path "$1"
}

abi_to_openssl_target() {
    case "$1" in
        arm64-v8a)   echo "android-arm64" ;;
        armeabi-v7a) echo "android-arm" ;;
        x86_64)      echo "android-x86_64" ;;
        x86)         echo "android-x86" ;;
        *) echo "unsupported ABI: $1" >&2; return 1 ;;
    esac
}

abi_to_toolchain_prefix() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64)      echo "x86_64-linux-android" ;;
        x86)         echo "i686-linux-android" ;;
        *) echo "unsupported ABI: $1" >&2; return 1 ;;
    esac
}

abi_to_clang_triple() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64)      echo "x86_64-linux-android" ;;
        x86)         echo "i686-linux-android" ;;
        *) echo "unsupported ABI: $1" >&2; return 1 ;;
    esac
}

deps_prefix_for_abi() {
    echo "${DEPS_DIR}/$1"
}

# ---------------------------------------------------------------------------
# JDK / Gradle
# ---------------------------------------------------------------------------
# The Gradle launcher is a shell script that runs as a *child* process, so
# JAVA_HOME has to be exported rather than merely set by candy.env. Its own
# lookup (`[ -x "$JAVA_HOME/bin/java" ]`) accepts the "C:/..." form fine under
# MSYS, so no conversion is needed here -- only an export.
if [[ -n "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME
    _jdk_bin="$(to_posix_path "${JAVA_HOME}")/bin"
    case ":${PATH}:" in
        *":${_jdk_bin}:"*) ;;
        *) export PATH="${_jdk_bin}:${PATH}" ;;
    esac
    unset _jdk_bin
fi

if [[ -n "${GRADLE_HOME:-}" ]]; then
    export GRADLE_HOME
fi
if [[ -n "${GRADLE:-}" ]]; then
    export GRADLE
fi
