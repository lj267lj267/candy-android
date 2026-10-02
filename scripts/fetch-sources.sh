#!/usr/bin/env bash
# Fetch the third-party sources the Android port compiles against.
#
#   ./scripts/fetch-sources.sh
#
# Downloads into <project>/deps-src and is idempotent: existing directories are
# left alone.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

OPENSSL_URL="https://codeload.github.com/openssl/openssl/zip/refs/tags/openssl-${OPENSSL_VERSION}"
POCO_URL="https://codeload.github.com/pocoproject/poco/zip/refs/tags/poco-${POCO_VERSION}-release"

mkdir -p "${THIRD_PARTY_DIR}"
cd "${THIRD_PARTY_DIR}"

download() {
    local url="$1" out="$2"
    if [[ -f "${out}" ]]; then
        echo "==> ${out} already downloaded"
        return
    fi
    echo "==> downloading ${url}"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 -o "${out}.part" "${url}"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "${out}.part" "${url}"
    else
        echo "error: neither curl nor wget is available" >&2
        exit 1
    fi
    mv "${out}.part" "${out}"
}

unzip_into() {
    local archive="$1" dest="$2"
    mkdir -p "${dest}"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "${archive}" -d "${dest}"
    else
        # Git for Windows and MSYS2 both ship a bsdtar-compatible tar.
        tar -xf "${archive}" -C "${dest}"
    fi
}

if [[ ! -f "${OPENSSL_SRC}/Configure" ]]; then
    download "${OPENSSL_URL}" "openssl-${OPENSSL_VERSION}.zip"
    unzip_into "openssl-${OPENSSL_VERSION}.zip" "${THIRD_PARTY_DIR}"
    extracted="${THIRD_PARTY_DIR}/openssl-openssl-${OPENSSL_VERSION}"
    if [[ -d "${extracted}" && ! -d "${OPENSSL_SRC}" ]]; then
        mv "${extracted}" "${OPENSSL_SRC}"
    fi
fi

if [[ ! -f "${POCO_SRC}/CMakeLists.txt" ]]; then
    download "${POCO_URL}" "poco-${POCO_VERSION}-release.zip"
    unzip_into "poco-${POCO_VERSION}-release.zip" "${THIRD_PARTY_DIR}"
    extracted="${THIRD_PARTY_DIR}/poco-poco-${POCO_VERSION}-release"
    if [[ -d "${extracted}" && ! -d "${POCO_SRC}" ]]; then
        mv "${extracted}" "${POCO_SRC}"
    fi
fi

echo "==> OpenSSL sources: ${OPENSSL_SRC}"
echo "==> Poco sources:    ${POCO_SRC}"
