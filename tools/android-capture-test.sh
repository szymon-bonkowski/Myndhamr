#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Physical acceptance: a skipped capture test must fail this gate.
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.szymonbonkowski.myndhamr.NativeFoundationDeviceTest,io.github.szymonbonkowski.myndhamr.CaptureDeviceTest --console=plain
python3 tools/check-device-tests.py --require-capture
