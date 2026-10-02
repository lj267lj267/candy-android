#!/usr/bin/env bash
# Cross-compile OpenSSL as a static library for one Android ABI.
#
#   ./scripts/build-openssl.sh arm64-v8a
#
# Expects the OpenSSL sources at $OPENSSL_SRC (see scripts/fetch-sources.sh) and
# the NDK to be reachable through $ANDROID_NDK_ROOT.
#
# Notes for the Windows host
# --------------------------
# OpenSSL's Configure finds the NDK through $ANDROID_NDK_ROOT plus a `clang` on
# $PATH, and it must run under a Perl that works. On Windows the MSYS Perl that
# ships with PortableGit is unusable (msys-perl5_42.dll is not shipped), so this
# script expects a native-Windows Perl such as Strawberry ($^O eq "MSWin32").
# Because that Perl cannot resolve MSYS "/c/..." paths, $ANDROID_NDK_ROOT and
# --prefix are normalised to "C:/..." form, and Configurations/15-android.conf
# carries a small patch that tolerates backslash separators from `which()`.
#
# OpenSSL refuses out-of-tree builds, so the sources are configured in place;
# only the installed prefix under native-deps/<abi> is consumed by the app.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

ABI="${1:-arm64-v8a}"
TARGET="$(abi_to_openssl_target "${ABI}")"
PREFIX="$(deps_prefix_for_abi "${ABI}")"

NDK_NATIVE="$(to_native_forward_path "${ANDROID_NDK_ROOT}")"
# PATH entries must stay in MSYS form, otherwise the shell cannot find clang.
NDK_POSIX="$(to_posix_path "${ANDROID_NDK_ROOT}")"
PREFIX_NATIVE="$(to_native_forward_path "${PREFIX}")"
TOOLCHAIN_POSIX="${NDK_POSIX}/toolchains/llvm/prebuilt/windows-x86_64"
if [[ ! -d "${TOOLCHAIN_POSIX}" ]]; then
    # Non-Windows hosts.
    TOOLCHAIN_POSIX="${NDK_POSIX}/toolchains/llvm/prebuilt/linux-x86_64"
fi
if [[ ! -d "${TOOLCHAIN_POSIX}" ]]; then
    echo "error: no LLVM toolchain under ${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt" >&2
    exit 1
fi

if [[ ! -f "${OPENSSL_SRC}/Configure" ]]; then
    echo "error: OpenSSL sources not found at ${OPENSSL_SRC}" >&2
    echo "       run scripts/fetch-sources.sh first" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Host patches
# ---------------------------------------------------------------------------
# Two files under Configurations/ assume a Unix Perl and have to be replaced
# when Configure runs under a native-Windows Perl. The patched copies live in
# scripts/openssl-overlays/<version>/ so a clean checkout builds without any
# manual editing of the downloaded tree (deps-src/ is not committed).
#   * 15-android.conf  -- resolve `which()`'s 8.3 short names before comparing
#                         them against $ANDROID_NDK_ROOT (see _which_native).
#   * unix-checker.pm  -- warn instead of die on backslash paths.
# Both are no-ops on a POSIX host, so the overlay is safe everywhere.
OVERLAY_DIR="${SCRIPT_DIR}/openssl-overlays/${OPENSSL_VERSION}"
if [[ -f "${OVERLAY_DIR}/Configurations/15-android.conf" ]]; then
    echo "==> applying host patches for OpenSSL ${OPENSSL_VERSION}"
    cp -f "${OVERLAY_DIR}/Configurations/15-android.conf" \
          "${OVERLAY_DIR}/Configurations/unix-checker.pm" \
          "${OPENSSL_SRC}/Configurations/"
else
    echo "warning: no overlay for OpenSSL ${OPENSSL_VERSION} in ${OVERLAY_DIR}." >&2
    echo "         A native-Windows Perl will not be able to detect the NDK." >&2
fi

# The NDK toolchain must be on PATH so that Configure's `which("clang")` picks
# up the NDK's clang rather than some host compiler.
export ANDROID_NDK_ROOT="${NDK_NATIVE}"
export PATH="${TOOLCHAIN_POSIX}/bin:${PATH}"

# Configure stores $PERL in configdata.pm and later runs
# `system("$perl configdata.pm")` through cmd.exe. A POSIX "/c/..." path (or a
# Windows "C:\..." path with backslashes) does not survive that round trip, so
# put the interpreter's directory on PATH and hand Configure the bare name.
if [[ -n "${PERL:-}" && ( "${PERL}" == */* || "${PERL}" == *\\* ) ]]; then
    PERL_DIR="$(dirname "${PERL}")"
    PATH="$(to_posix_path "${PERL_DIR}"):${PATH}"
    PERL="$(basename "${PERL}")"
fi
export PATH
export PERL="${PERL:-perl}"

echo "==> perl        : ${PERL}"
if ! command -v "${PERL:-perl}" >/dev/null 2>&1 && [[ ! -x "${PERL:-perl}" ]]; then
    echo "error: no usable perl. On Windows install Strawberry Perl and set PERL." >&2
    exit 1
fi
echo "==> ndk         : ${ANDROID_NDK_ROOT}"
echo "==> which clang : $(command -v clang || echo NOT-FOUND)"

cd "${OPENSSL_SRC}"

# Re-configuring an already-configured tree needs a clean slate.
if [[ -f Makefile ]]; then
    "${MAKE:-make}" clean >/dev/null 2>&1 || true
fi

# Configure picks up the API level from CPPFLAGS (it does not accept -D... as a
# command-line option) and stores it in the generated Makefile.
export CPPFLAGS="-D__ANDROID_API__=${ANDROID_API}"

echo "==> configuring OpenSSL ${OPENSSL_VERSION} for ${ABI} (${TARGET}, API ${ANDROID_API})"
"${PERL:-perl}" Configure "${TARGET}" \
    --prefix="${PREFIX_NATIVE}" \
    --openssldir="${PREFIX_NATIVE}/ssl" \
    no-shared \
    no-tests \
    no-ui-console \
    no-engine \
    -fPIC

# A native-Windows Perl renders every path in the generated Unix Makefile with
# backslashes ("crypto\bn\bn_add.o"), which `make` cannot use.  Rewrite the
# separators only where a backslash sits between two word characters -- that is
# exactly the path-separator case, and it leaves Makefile line continuations
# and shell/regex escapes (\! \" \\$$ '\\n' '\.') alone.  On a POSIX host the
# count is 0 and nothing is written.
echo "==> normalising path separators"
"${PERL:-perl}" -e '
    my $total = 0;
    for my $f (@ARGV) {
        next unless -f $f;
        open my $in, "<", $f or die "$f: $!";
        local $/;
        my $t = <$in>;
        close $in;
        my $n = ($t =~ s/(\w)\\(\w)/$1\/$2/g);
        if ($n) {
            open my $out, ">", $f or die "$f: $!";
            print $out $t;
            close $out;
        }
        print "    $f: $n separator(s) rewritten\n";
        $total += $n;
    }
' Makefile Makefile.in

# Build the libraries only -- never the default target. `all` expands to
# build_sw, which also builds the aarch64 `openssl` CLI, and that fails here:
# `no-ui-console` makes configuration.h define OPENSSL_NO_UI_CONSOLE, which
# drops apps/dgst.c and apps/enc.c out of libapps.a while apps/openssl.c keeps
# referencing dgst_main/enc_main. `build_libs` stops at libcrypto.a/libssl.a.
echo "==> building libraries with ${JOBS} jobs"
"${MAKE:-make}" -j"${JOBS}" build_libs

if [[ ! -f libcrypto.a || ! -f libssl.a ]]; then
    echo "error: build did not produce libcrypto.a / libssl.a" >&2
    exit 1
fi

# Copy the artifacts directly. `make install_sw` would also install the
# aarch64 `openssl` binary and pkg-config files, none of which the app needs,
# and its install rules are the least portable part of the tree on Windows.
echo "==> installing into ${PREFIX}"
mkdir -p "${PREFIX}/include" "${PREFIX}/lib"
rm -rf "${PREFIX}/include/openssl"
cp -R include/openssl "${PREFIX}/include/openssl"
cp -f libcrypto.a libssl.a "${PREFIX}/lib/"

ls -l "${PREFIX}/lib"
echo "==> OpenSSL for ${ABI} done"
