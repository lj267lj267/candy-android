#!/usr/bin/env bash
# Cross-compile the Poco modules candy needs as static libraries for one ABI.
#
#   ./scripts/build-poco.sh arm64-v8a
#
# Only Foundation / XML / JSON / Net / NetSSL / Crypto / Util are built; every
# other module is switched off to keep the APK (and the build time) down.
#
# Windows host note: OpenSSL is located by passing OPENSSL_SSL_LIBRARY and
# OPENSSL_CRYPTO_LIBRARY as absolute paths. Letting FindOpenSSL search for them
# fails here -- CMAKE_FIND_ROOT_PATH pins find_library() to the NDK sysroot, and
# a Windows host does not have ".a" among its default library suffixes. A
# pre-seeded cache entry makes find_library() a no-op, so the paths win.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

ABI="${1:-arm64-v8a}"
PREFIX="$(deps_prefix_for_abi "${ABI}")"
BUILD_DIR="${PROJECT_ROOT}/native-deps/build/poco/${ABI}"
NDK_NATIVE="$(to_windows_path "${ANDROID_NDK_ROOT}")"
PREFIX_NATIVE="$(to_windows_path "${PREFIX}")"
POCO_SRC_NATIVE="$(to_windows_path "${POCO_SRC}")"
BUILD_DIR_NATIVE="$(to_windows_path "${BUILD_DIR}")"

if [[ ! -f "${POCO_SRC}/CMakeLists.txt" ]]; then
    echo "error: Poco sources not found at ${POCO_SRC}" >&2
    echo "       run scripts/fetch-sources.sh first" >&2
    exit 1
fi

if [[ ! -f "${PREFIX}/lib/libcrypto.a" ]]; then
    echo "error: OpenSSL for ${ABI} is missing; run scripts/build-openssl.sh ${ABI} first" >&2
    exit 1
fi

CMAKE="${CMAKE:-cmake}"
if ! command -v "${CMAKE}" >/dev/null 2>&1 && [[ ! -x "${CMAKE}" ]]; then
    for candidate in \
        "${CMAKE_DIR:-}/bin/cmake.exe" \
        "${ANDROID_HOME:-}/cmake/3.22.1/bin/cmake.exe" \
        ${ANDROID_HOME:-}/cmake/*/bin/cmake.exe \
        "${ANDROID_HOME:-}/cmake/3.22.1/bin/cmake" \
        ${ANDROID_HOME:-}/cmake/*/bin/cmake
    do
        if [[ -n "${candidate}" && -x "${candidate}" ]]; then
            CMAKE="${candidate}"
            break
        fi
    done
fi
if [[ ! -x "${CMAKE}" ]] && ! command -v "${CMAKE}" >/dev/null 2>&1; then
    echo "error: cmake not found; set CMAKE (or CMAKE_DIR) in scripts/candy.env" >&2
    exit 1
fi

NINJA="${NINJA:-}"
if [[ -z "${NINJA}" ]]; then
    for candidate in \
        "${CMAKE_DIR:-}/bin/ninja.exe" \
        "${CMAKE_DIR:-}/bin/ninja" \
        "${ANDROID_HOME:-}/cmake/3.22.1/bin/ninja.exe" \
        ${ANDROID_HOME:-}/cmake/*/bin/ninja.exe
    do
        if [[ -n "${candidate}" && -x "${candidate}" ]]; then
            NINJA="${candidate}"
            break
        fi
    done
fi

GENERATOR_ARGS=()
if [[ -n "${NINJA}" ]]; then
    GENERATOR_ARGS=(-G Ninja "-DCMAKE_MAKE_PROGRAM=$(to_windows_path "${NINJA}")")
else
    GENERATOR_ARGS=(-G "Unix Makefiles")
fi

echo "==> configuring Poco ${POCO_VERSION} for ${ABI}"
"${CMAKE}" \
    -S "${POCO_SRC_NATIVE}" \
    -B "${BUILD_DIR_NATIVE}" \
    "${GENERATOR_ARGS[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_NATIVE}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${PREFIX_NATIVE}" \
    -DCMAKE_PREFIX_PATH="${PREFIX_NATIVE}" \
    -DOPENSSL_ROOT_DIR="${PREFIX_NATIVE}" \
    -DOPENSSL_USE_STATIC_LIBS=TRUE \
    -DOPENSSL_INCLUDE_DIR="${PREFIX_NATIVE}/include" \
    -DOPENSSL_SSL_LIBRARY="${PREFIX_NATIVE}/lib/libssl.a" \
    -DOPENSSL_CRYPTO_LIBRARY="${PREFIX_NATIVE}/lib/libcrypto.a" \
    -DBUILD_SHARED_LIBS=OFF \
    -DPOCO_STATIC=ON \
    -DENABLE_FOUNDATION=ON \
    -DENABLE_XML=ON \
    -DENABLE_JSON=ON \
    -DENABLE_NET=ON \
    -DENABLE_NETSSL=ON \
    -DENABLE_NETSSL_WIN=OFF \
    -DENABLE_CRYPTO=ON \
    -DENABLE_UTIL=ON \
    -DENABLE_DATA=OFF \
    -DENABLE_DATA_SQLITE=OFF \
    -DENABLE_DATA_MYSQL=OFF \
    -DENABLE_DATA_ODBC=OFF \
    -DENABLE_DATA_POSTGRESQL=OFF \
    -DENABLE_MONGODB=OFF \
    -DENABLE_REDIS=OFF \
    -DENABLE_ENCODINGS=OFF \
    -DENABLE_ENCODINGS_COMPILER=OFF \
    -DENABLE_PROMETHEUS=OFF \
    -DENABLE_PAGECOMPILER=OFF \
    -DENABLE_PAGECOMPILER_FILE2PAGE=OFF \
    -DENABLE_ACTIVERECORD=OFF \
    -DENABLE_ACTIVERECORD_COMPILER=OFF \
    -DENABLE_APACHECONNECTOR=OFF \
    -DENABLE_CPPPARSER=OFF \
    -DENABLE_POCODOC=OFF \
    -DENABLE_PDF=OFF \
    -DENABLE_SEVENZIP=OFF \
    -DENABLE_ZIP=OFF \
    -DENABLE_JWT=OFF \
    -DENABLE_TESTS=OFF \
    -DENABLE_SAMPLES=OFF \
    -DENABLE_COMPILER_WARNINGS=OFF

echo "==> building"
"${CMAKE}" --build "${BUILD_DIR_NATIVE}" --config Release --parallel "${JOBS}"

echo "==> installing into ${PREFIX}"
"${CMAKE}" --install "${BUILD_DIR_NATIVE}" --config Release

ls -l "${PREFIX}/lib"
echo "==> Poco for ${ABI} done"
