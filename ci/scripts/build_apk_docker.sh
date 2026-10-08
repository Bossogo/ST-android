#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="st-android-build:local"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${ROOT_DIR}"

docker build -f ci/docker/Dockerfile -t "${IMAGE_NAME}" .

DOCKER_ENV_ARGS=()
if [ -n "${VERSION_NAME:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "VERSION_NAME=${VERSION_NAME}")
fi
if [ -n "${VERSION_CODE:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "VERSION_CODE=${VERSION_CODE}")
fi
if [ -n "${RELEASE_KEYSTORE_B64:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "RELEASE_KEYSTORE_B64=${RELEASE_KEYSTORE_B64}")
fi
if [ -n "${RELEASE_STORE_PASSWORD:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "RELEASE_STORE_PASSWORD=${RELEASE_STORE_PASSWORD}")
fi
if [ -n "${RELEASE_KEY_ALIAS:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "RELEASE_KEY_ALIAS=${RELEASE_KEY_ALIAS}")
fi
if [ -n "${RELEASE_KEY_PASSWORD:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "RELEASE_KEY_PASSWORD=${RELEASE_KEY_PASSWORD}")
fi
if [ -n "${GITHUB_REF_TYPE:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "GITHUB_REF_TYPE=${GITHUB_REF_TYPE}")
fi
if [ -n "${GITHUB_REF_NAME:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "GITHUB_REF_NAME=${GITHUB_REF_NAME}")
fi
if [ -n "${GITHUB_REF:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "GITHUB_REF=${GITHUB_REF}")
fi
if [ -n "${GITHUB_RUN_NUMBER:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "GITHUB_RUN_NUMBER=${GITHUB_RUN_NUMBER}")
fi
if [ -n "${SKIP_NODE_BUILD:-}" ]; then
  DOCKER_ENV_ARGS+=(-e "SKIP_NODE_BUILD=${SKIP_NODE_BUILD}")
fi

docker run --rm \
  -u "$(id -u):$(id -g)" \
  -v "${ROOT_DIR}:/workspace" \
  -w /workspace \
  "${DOCKER_ENV_ARGS[@]}" \
  "${IMAGE_NAME}" \
  bash -lc '\
    set -euo pipefail; \
    export HOME="/workspace/.home"; \
    export NPM_CONFIG_CACHE="${NPM_CONFIG_CACHE:-/tmp/.npm}"; \
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.gradle}"; \
    export ANDROID_USER_HOME="/workspace/.android"; \
    unset ANDROID_SDK_HOME ANDROID_PREFS_ROOT; \
    mkdir -p "$HOME" "$NPM_CONFIG_CACHE" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"; \
    touch "$ANDROID_USER_HOME/repositories.cfg" 2>/dev/null || true; \
    BUILD_MODE="release"; \
    if [ -n "${RELEASE_KEYSTORE_B64:-}" ]; then \
      mkdir -p ci/keystore; \
      echo "$RELEASE_KEYSTORE_B64" | base64 -d > ci/keystore/release.jks; \
      chmod 600 ci/keystore/release.jks; \
      export RELEASE_STORE_FILE="/workspace/ci/keystore/release.jks"; \
      if [ -z "${RELEASE_KEY_PASSWORD:-}" ] && [ -n "${RELEASE_STORE_PASSWORD:-}" ]; then \
        export RELEASE_KEY_PASSWORD="$RELEASE_STORE_PASSWORD"; \
      fi; \
    else \
      echo "No RELEASE_KEYSTORE_B64 secret — assembling release APK with the debug keystore (installable for sideload)."; \
    fi; \
    NDK_ROOT="${ANDROID_NDK_HOME:-}"; \
    if [ -z "$NDK_ROOT" ]; then \
      NDK_ROOT="$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | head -n1 || true)"; \
    fi; \
    if [ -z "$NDK_ROOT" ]; then \
      NDK_ROOT="$(ls -d /opt/android-sdk-linux/ndk/* 2>/dev/null | head -n1 || true)"; \
    fi; \
    if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT" ]; then \
      echo "Android NDK not found in container"; \
      exit 1; \
    fi; \
    TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"; \
    if [ ! -d "$TOOLCHAIN" ]; then \
      echo "NDK toolchain not found at $TOOLCHAIN"; \
      exit 1; \
    fi; \
    export ANDROID_NDK_HOME="$NDK_ROOT"; \
    export ANDROID_API=26; \
    ABI_TRIPLE="aarch64-linux-android"; \
    SYSROOT_LIB="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"; \
    LIBCXX_PATH="$SYSROOT_LIB/$ABI_TRIPLE/$ANDROID_API/libc++_shared.so"; \
    if [ ! -f "$LIBCXX_PATH" ]; then \
      LIBCXX_PATH="$SYSROOT_LIB/$ABI_TRIPLE/libc++_shared.so"; \
    fi; \
    if [ ! -f "$LIBCXX_PATH" ]; then \
      echo "Expected libc++_shared.so not found for $ABI_TRIPLE (API $ANDROID_API) under $SYSROOT_LIB"; \
      find "$SYSROOT_LIB" -path "*/libc++_shared.so" -print || true; \
      exit 1; \
    fi; \
    python3 ci/scripts/check_elf_align.py "$TOOLCHAIN/bin/llvm-readelf" "$LIBCXX_PATH"; \
    mkdir -p out/android/arm64 app/src/main/jniLibs/arm64-v8a; \
    CACHED_NODE="/workspace/out/android/arm64/node"; \
    CACHED_LIBCXX="/workspace/out/android/arm64/libc++_shared.so"; \
    if [ "${SKIP_NODE_BUILD:-0}" = "1" ] && [ -f "$CACHED_NODE" ] && [ -s "$CACHED_NODE" ]; then \
      echo "SKIP_NODE_BUILD=1 and cached node present — skipping Node cross-compile"; \
      cp -f "$CACHED_NODE" app/src/main/jniLibs/arm64-v8a/libnode.so; \
      if [ -f "$CACHED_LIBCXX" ]; then \
        cp -f "$CACHED_LIBCXX" app/src/main/jniLibs/arm64-v8a/libc++_shared.so; \
      else \
        cp -f "$LIBCXX_PATH" app/src/main/jniLibs/arm64-v8a/libc++_shared.so; \
        cp -f "$LIBCXX_PATH" "$CACHED_LIBCXX"; \
      fi; \
    else \
      if [ "${SKIP_NODE_BUILD:-0}" = "1" ]; then \
        echo "SKIP_NODE_BUILD=1 but cached node missing — building Node"; \
      fi; \
      ./tools/node/scripts/build_node_android.sh arm64; \
      cp -f out/android/arm64/node app/src/main/jniLibs/arm64-v8a/libnode.so; \
      cp -f "$LIBCXX_PATH" app/src/main/jniLibs/arm64-v8a/libc++_shared.so; \
      cp -f "$LIBCXX_PATH" out/android/arm64/libc++_shared.so; \
    fi; \
    python3 ci/scripts/check_elf_align.py "$TOOLCHAIN/bin/llvm-readelf" \
      app/src/main/jniLibs/arm64-v8a/libnode.so \
      app/src/main/jniLibs/arm64-v8a/libc++_shared.so; \
    bash ci/scripts/build_st_bundle.sh; \
    if [ "$BUILD_MODE" = "release" ]; then \
      gradle :app:assembleRelease --stacktrace --no-daemon; \
    else \
      gradle :app:assembleDebug --stacktrace --no-daemon; \
    fi; \
  '

APK_PATH=""
BUILD_KIND=""
if [ -f "${ROOT_DIR}/app/build/outputs/apk/release/app-release.apk" ]; then
  APK_PATH="${ROOT_DIR}/app/build/outputs/apk/release/app-release.apk"
  BUILD_KIND="release"
elif [ -f "${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk" ]; then
  APK_PATH="${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
  BUILD_KIND="debug"
fi

if [ -z "${APK_PATH}" ]; then
  printf '\nAPK not found\n'
  exit 1
fi

mkdir -p "${ROOT_DIR}/out"
# Prefer an explicit VERSION_NAME, then a git tag name, else the Actions run number.
# Never use branch/PR ref names here — they contain '/' (e.g. cursor/... or 1/merge)
# and break the staged APK path.
VERSION_LABEL="${VERSION_NAME:-}"
if [ -z "${VERSION_LABEL}" ] && [ "${GITHUB_REF_TYPE:-}" = "tag" ] && [ -n "${GITHUB_REF_NAME:-}" ]; then
  VERSION_LABEL="${GITHUB_REF_NAME}"
fi
VERSION_LABEL="${VERSION_LABEL#v}"
if [ -z "${VERSION_LABEL}" ]; then
  VERSION_LABEL="${GITHUB_RUN_NUMBER:-local}"
fi
# Final filesystem-safe sanitization for any remaining odd characters.
VERSION_LABEL="$(printf '%s' "${VERSION_LABEL}" | tr -c 'A-Za-z0-9._-' '_')"

OUT_NAME="TavernPocket-${VERSION_LABEL}-${BUILD_KIND}.apk"
cp -f "${APK_PATH}" "${ROOT_DIR}/out/${OUT_NAME}"
printf '\nAPK: %s\n' "${ROOT_DIR}/out/${OUT_NAME}"
printf 'Source: %s\n' "${APK_PATH}"
