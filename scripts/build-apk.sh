#!/usr/bin/env bash
# Build the APK.
#
#   ./scripts/build-apk.sh              # debug APK, arm64-v8a
#   ./scripts/build-apk.sh release      # release APK (signed with the debug key)
#
# Requires: JDK 17, the Android SDK, and native-deps/<abi> to have been built by
# scripts/build-native-deps.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

VARIANT="${1:-debug}"
case "${VARIANT}" in
    debug|Debug)   TASK="assembleDebug" ;;
    release|Release) TASK="assembleRelease" ;;
    *) TASK="assemble${VARIANT}" ;;
esac

cd "${PROJECT_ROOT}"

{
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        echo "sdk.dir=$(to_windows_path "${ANDROID_HOME}")"
    fi
    if [[ -n "${CMAKE_DIR:-}" ]]; then
        echo "cmake.dir=$(to_windows_path "${CMAKE_DIR}")"
    fi
} > local.properties

# Prefer the project's own wrapper; otherwise fall back to a locally installed
# Gradle (this is what the CI-less Windows toolchain uses, where unpacking the
# distribution is cheaper than letting the wrapper re-download it).
if [[ -n "${GRADLE:-}" ]]; then
    GRADLE_CMD=("${GRADLE}")
elif [[ -x "${PROJECT_ROOT}/gradlew" ]]; then
    GRADLE_CMD=("${PROJECT_ROOT}/gradlew")
elif [[ -n "${GRADLE_HOME:-}" && -x "${GRADLE_HOME}/bin/gradle" ]]; then
    GRADLE_CMD=("${GRADLE_HOME}/bin/gradle")
else
    echo "error: no gradle found; set GRADLE=/path/to/gradle or GRADLE_HOME" >&2
    exit 1
fi

echo "==> ${TASK} (${GRADLE_CMD[0]})"
"${GRADLE_CMD[@]}" --no-daemon "${TASK}"

echo
echo "==> artifacts:"
find "${PROJECT_ROOT}/app/build/outputs/apk" -name '*.apk' -print 2>/dev/null || true
