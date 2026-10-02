#!/usr/bin/env bash
# Build every native dependency the APK needs.
#
#   ./scripts/build-native-deps.sh                 # arm64-v8a (default)
#   ./scripts/build-native-deps.sh arm64-v8a x86_64
#
# Produces native-deps/<abi>/{include,lib} and then overwrites the vendored
# candy core with a pristine copy of upstream, so a rebuild always starts from
# the real sources.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

ABIS=("$@")
if [[ ${#ABIS[@]} -eq 0 ]]; then
    ABIS=("arm64-v8a")
fi

"${SCRIPT_DIR}/fetch-sources.sh"

for abi in "${ABIS[@]}"; do
    echo
    echo "################ ${abi} ################"
    "${SCRIPT_DIR}/build-openssl.sh" "${abi}"
    "${SCRIPT_DIR}/build-poco.sh" "${abi}"
done

echo
echo "==> native dependencies ready:"
for abi in "${ABIS[@]}"; do
    echo "    ${DEPS_DIR}/${abi}"
done
