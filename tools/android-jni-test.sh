#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :androidApp:connectedDebugAndroidTest --no-configuration-cache \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.szymonbonkowski.myndhamr.NativeFoundationDeviceTest --console=plain
python3 tools/check-device-tests.py
