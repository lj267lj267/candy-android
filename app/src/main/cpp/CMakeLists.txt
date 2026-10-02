cmake_minimum_required(VERSION 3.22.1)
project(candycore CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ---------------------------------------------------------------------------
# Prebuilt third-party dependencies (Poco + OpenSSL), produced by
# scripts/build-native-deps.sh.
# ---------------------------------------------------------------------------
set(CANDY_DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native-deps/${ANDROID_ABI}"
    CACHE PATH "Directory holding the prebuilt Poco/OpenSSL trees for this ABI")

if(NOT EXISTS "${CANDY_DEPS_DIR}/lib/libPocoFoundation.a")
    message(FATAL_ERROR
        "Missing prebuilt dependencies for ABI '${ANDROID_ABI}'.\n"
        "Expected: ${CANDY_DEPS_DIR}/lib/libPocoFoundation.a\n"
        "Run scripts/build-native-deps.sh first (see README.md).")
endif()

# ---------------------------------------------------------------------------
# Vendored candy core (upstream candy/include + candy/src, see UPSTREAM_VERSION.txt).
# The upstream CMakeLists is deliberately unused: it pulls Poco/OpenSSL in via
# FetchContent, which cannot work here.
# ---------------------------------------------------------------------------
set(CANDY_VENDOR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/candy")

file(GLOB_RECURSE CANDY_CORE_SOURCES CONFIGURE_DEPENDS "${CANDY_VENDOR_DIR}/src/*.cc")

add_library(candycore SHARED
    ${CANDY_CORE_SOURCES}
    tun_android.cc
    jvm_bridge.cc
    candy_jni.cc
)

target_include_directories(candycore PRIVATE
    "${CANDY_VENDOR_DIR}/include"
    "${CANDY_VENDOR_DIR}/src"
    "${CANDY_DEPS_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

# The Kotlin side always sends an explicit version so that only MainActivity has
# to be touched when the vendored core is refreshed.
target_compile_definitions(candycore PRIVATE CANDY_VERSION="${CANDY_CORE_VERSION}")

target_compile_options(candycore PRIVATE
    -Wall
    -Wno-unused-parameter
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(candycore PRIVATE
    -Wl,--gc-sections
    # Keep Poco/OpenSSL symbols out of the exported symbol table: the dynamic
    # linker must never resolve them against a different libcrypto in the app.
    -Wl,--exclude-libs,ALL
)

# Static Poco/OpenSSL: group them so circular references between the archives
# resolve regardless of the order they are listed in.
target_link_libraries(candycore PRIVATE
    -Wl,--start-group
    "${CANDY_DEPS_DIR}/lib/libPocoNetSSL.a"
    "${CANDY_DEPS_DIR}/lib/libPocoCrypto.a"
    "${CANDY_DEPS_DIR}/lib/libPocoUtil.a"
    "${CANDY_DEPS_DIR}/lib/libPocoNet.a"
    "${CANDY_DEPS_DIR}/lib/libPocoJSON.a"
    "${CANDY_DEPS_DIR}/lib/libPocoXML.a"
    "${CANDY_DEPS_DIR}/lib/libPocoFoundation.a"
    "${CANDY_DEPS_DIR}/lib/libssl.a"
    "${CANDY_DEPS_DIR}/lib/libcrypto.a"
    -Wl,--end-group
    android
    log
    dl
    z
)
