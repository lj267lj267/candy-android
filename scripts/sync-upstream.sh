#!/usr/bin/env bash
# Refresh the vendored candy core from upstream.
#
#   ./scripts/sync-upstream.sh            # master
#   ./scripts/sync-upstream.sh v6.1.11    # a tag
#
# The vendored copy under app/src/main/cpp/third_party/candy is a verbatim
# snapshot of upstream candy/include + candy/src, minus the platform TUN
# implementations that cannot apply on Android (linux.cc / macos.cc /
# windows.cc / unknown.cc, replaced by tun_android.cc).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

REF="${1:-master}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

VENDOR="${PROJECT_ROOT}/app/src/main/cpp/third_party/candy"
URL="https://codeload.github.com/lanthora/candy/zip/refs/heads/${REF}"
if [[ "${REF}" == v* ]]; then
    URL="https://codeload.github.com/lanthora/candy/zip/refs/tags/${REF}"
fi

echo "==> fetching ${URL}"
if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "${WORK}/candy.zip" "${URL}"
else
    wget -O "${WORK}/candy.zip" "${URL}"
fi

mkdir -p "${WORK}/src"
if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "${WORK}/candy.zip" -d "${WORK}/src"
else
    tar -xf "${WORK}/candy.zip" -C "${WORK}/src"
fi
UPSTREAM="$(find "${WORK}/src" -maxdepth 1 -mindepth 1 -type d | head -n1)"
echo "==> upstream root: ${UPSTREAM}"

rm -rf "${VENDOR}/include" "${VENDOR}/src"
mkdir -p "${VENDOR}"
cp -a "${UPSTREAM}/candy/include" "${VENDOR}/include"
cp -a "${UPSTREAM}/candy/src" "${VENDOR}/src"
cp -f "${UPSTREAM}/LICENSE" "${VENDOR}/LICENSE"

# The Android TUN implementation replaces all four of these.
rm -f "${VENDOR}/src/tun/linux.cc" \
      "${VENDOR}/src/tun/macos.cc" \
      "${VENDOR}/src/tun/windows.cc" \
      "${VENDOR}/src/tun/unknown.cc"

VERSION="$(grep -o 'project(Candy VERSION [0-9.]*)' "${UPSTREAM}/CMakeLists.txt" | awk '{print $3}' | tr -d ')')"
{
    echo "Source: https://github.com/lanthora/candy"
    echo "Ref: ${REF}"
    echo "Project version: ${VERSION}"
    echo "Synced at: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo
    echo "Only candy/include and candy/src are vendored; the upstream CMakeLists is"
    echo "intentionally unused (it pulls Poco/OpenSSL in through FetchContent)."
    echo "src/tun/{linux,macos,windows,unknown}.cc are removed because"
    echo "app/src/main/cpp/tun_android.cc provides Tun::Impl on Android instead."
} > "${VENDOR}/UPSTREAM_VERSION.txt"

echo "==> vendored candy ${VERSION} (${REF})"
echo "    remember to update candyCoreVersion in app/build.gradle.kts if it changed"
