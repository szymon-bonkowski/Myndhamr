#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK with NDK 28.2.13676358}"
abi="${MYNDHAMR_ANDROID_ABI:-arm64-v8a}"
./gradlew nativeBuild --console=plain
cmake -S native -B "build/native-android-$abi" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release \
  -DMYNDHAMR_HOST_PROTOC="$PWD/build/native/_deps/protobuf-build/protoc" \
  -DMYNDHAMR_BUILD_JNI=OFF -DMYNDHAMR_BUILD_BENCHMARKS=ON -DBUILD_TESTING=ON
cmake --build "build/native-android-$abi" --parallel 4 --target myndhamr_native_tests myndhamr_benchmark
if [[ "${MYNDHAMR_BUILD_ONLY:-0}" == 1 ]]; then exit 0; fi
adb get-state
remote="$(adb shell mktemp -d /data/local/tmp/myndhamr-v0-XXXXXX | tr -d '\r')"
trap 'adb shell rm -rf "$remote" >/dev/null' EXIT
adb push "build/native-android-$abi/myndhamr_native_tests" "$remote/"
adb push "build/native-android-$abi/myndhamr_benchmark" "$remote/"
adb shell "chmod 700 '$remote/myndhamr_native_tests' '$remote/myndhamr_benchmark' && '$remote/myndhamr_native_tests' && '$remote/myndhamr_benchmark'"
