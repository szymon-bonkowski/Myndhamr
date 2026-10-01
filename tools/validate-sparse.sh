#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python_runtime="${MYNDHAMR_SPARSE_PYTHON:-$PWD/build/v02-venv/bin/python}"
if [[ ! -x "$python_runtime" ]]; then
  python3 -m venv build/v02-venv
fi
requirements_digest="$(sha256sum desktop-workers/colmap-adapter/requirements.txt | cut -d' ' -f1)"
marker="$PWD/build/sparse-requirements.sha256"
if [[ ! -f "$marker" ]] || [[ "$(cat "$marker")" != "$requirements_digest" ]]; then
  "$python_runtime" -m pip install --disable-pip-version-check -r desktop-workers/colmap-adapter/requirements.txt
  printf '%s\n' "$requirements_digest" > "$marker"
fi
./gradlew --console=plain nativeTest :desktopApp:test :desktopApp:installDist
"$python_runtime" -m unittest discover -s desktop-workers/colmap-adapter/tests -v
"$python_runtime" -m unittest discover -s desktop-workers/reconstruction-core/tests -v
acceptance_dir="$(mktemp -d "$PWD/build/sparse-acceptance-XXXXXX")"
"$python_runtime" tools/render-sparse-fixture.py "$acceptance_dir/render"
./gradlew --console=plain :desktopApp:generateSparseFixture -PfixtureRender="$acceptance_dir/render" -PfixtureScan="$acceptance_dir/fixture.scan3d"
desktopApp/build/install/desktopApp/bin/desktopApp reconstruct "$acceptance_dir/fixture.scan3d" "$acceptance_dir/run" --python "$python_runtime" > "$acceptance_dir/cli.json"
python3 tools/check-sparse-acceptance.py "$acceptance_dir/run" --controlled
printf 'Sparse acceptance artifacts: %s\n' "$acceptance_dir"
