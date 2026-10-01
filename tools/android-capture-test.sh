#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Physical acceptance: a skipped capture test must fail this gate.
./gradlew :androidApp:connectedDebugAndroidTest --no-configuration-cache --console=plain
python3 tools/check-device-tests.py --require-capture
