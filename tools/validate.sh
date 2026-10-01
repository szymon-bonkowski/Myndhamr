#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/check-inventory.py
./gradlew --console=plain \
  :shared:jvmTest :shared:domain:jvmTest :shared:scan-format:test \
  :desktopApp:build :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  :androidApp:lintDebug nativeTest verifyProtoGeneration benchmarkSmoke
python3 tests/tooling/test_proto_generation.py
./gradlew --console=plain :desktopApp:run --args=--help
