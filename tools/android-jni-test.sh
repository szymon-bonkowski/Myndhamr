#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :androidApp:connectedDebugAndroidTest --console=plain
python3 tools/check-device-tests.py
