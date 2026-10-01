#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <explicit-adb-serial> <app-files-relative-path> <output-prefix> [application-id]" >&2
  echo "Example: $0 R3GL60EQ96H projects/session.scan3d ./session io.github.szymonbonkowski.myndhamr" >&2
}

if [[ $# -lt 3 || $# -gt 4 ]]; then usage; exit 2; fi
serial="$1"
relative_path="$2"
output_prefix="$3"
package="${4:-io.github.szymonbonkowski.myndhamr}"

if [[ -z "$serial" || "$serial" == "-" || "$relative_path" = /* || "$relative_path" == *..* || "$relative_path" == *$'\n'* ]]; then
  echo "Serial must be explicit and path must stay below the app files directory" >&2
  exit 2
fi
command -v adb >/dev/null || { echo "adb is required" >&2; exit 2; }
adb -s "$serial" get-state >/dev/null

archive="${output_prefix}.tar"
metadata="${output_prefix}.json"
mkdir -p "$(dirname "$output_prefix")"
adb -s "$serial" exec-out run-as "$package" tar -C files -cf - "$relative_path" > "$archive"
python3 - "$serial" "$package" "$relative_path" "$archive" "$metadata" <<'PY'
import hashlib, json, os, sys, time
serial, package, remote_path, archive, metadata = sys.argv[1:]
digest = hashlib.sha256()
with open(archive, "rb") as source:
    for block in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(block)
record = {
    "format": "myndhamr-capture-collection-v1",
    "collected_unix_ns": time.time_ns(),
    "device_serial": serial,
    "application_id": package,
    "app_files_path": remote_path,
    "archive": os.path.basename(archive),
    "archive_bytes": os.path.getsize(archive),
    "archive_sha256": digest.hexdigest(),
}
with open(metadata, "w", encoding="utf-8") as destination:
    json.dump(record, destination, indent=2, sort_keys=True)
    destination.write("\n")
PY
echo "Wrote $archive and $metadata"
