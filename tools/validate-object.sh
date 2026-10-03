#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
object_python="${MYNDHAMR_OBJECT_PYTHON:-$PWD/build/v03-ci-venv/bin/python}"
if [[ ! -x "$object_python" ]]; then
  python3 -m venv build/v03-ci-venv
fi
if [[ -z "${MYNDHAMR_OBJECT_PYTHON:-}" ]]; then
  "$object_python" -m pip install --disable-pip-version-check -r desktop-workers/object-mesh/requirements.txt -r desktop-workers/object-mesh/requirements-test.txt
fi
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=4
# Optional NVIDIA wheel runtime used for local tests; normal CI uses CPU pycolmap.
object_site="$("$object_python" -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')"
export LD_LIBRARY_PATH="$object_site/nvidia/cuda_runtime/lib:$object_site/nvidia/curand/lib:${LD_LIBRARY_PATH:-}"
"$object_python" -m unittest discover -s desktop-workers/object-mesh/tests -v
"$object_python" tools/benchmark-object.py
