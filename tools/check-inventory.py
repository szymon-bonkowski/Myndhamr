#!/usr/bin/env python3
"""Check direct dependency inventory coverage and pinned version/license metadata."""

from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
from pathlib import Path


KNOWN_LICENSES = {"Apache-2.0", "BSD-3-Clause", "EPL-1.0", "MIT", "N/A"}
COPYLEFT_LICENSES = {
    "AGPL-3.0",
    "GPL-2.0",
    "GPL-3.0",
    "LGPL-2.1",
    "LGPL-3.0",
    "EPL-1.0",
}


def fail(message: str) -> None:
    print(f"inventory check: {message}", file=sys.stderr)


def resolved_version(entry: dict, versions: dict) -> str | None:
    if "version" in entry:
        value = entry["version"]
        if isinstance(value, dict):
            if "ref" in value:
                return versions.get(value["ref"])
            if "require" in value:
                return str(value["require"])
        return str(value)
    if "version.ref" in entry:
        return versions.get(entry["version.ref"])
    return None


def check_catalog(root: Path, catalog: dict, inventory: dict) -> list[str]:
    errors: list[str] = []
    toml_path = root / "gradle/libs.versions.toml"
    data = tomllib.loads(toml_path.read_text(encoding="utf-8"))
    versions = data.get("versions", {})
    catalog_data = inventory.get("catalog", {})

    for kind in ("libraries", "plugins"):
        declared = data.get(kind, {})
        recorded = catalog_data.get(kind, {})
        missing = sorted(set(declared) - set(recorded))
        stale = sorted(set(recorded) - set(declared))
        if missing:
            errors.append(f"catalog {kind} missing from inventory: {', '.join(missing)}")
        if stale:
            errors.append(f"inventory has unknown {kind} aliases: {', '.join(stale)}")
        for alias in sorted(set(declared) & set(recorded)):
            source = declared[alias]
            row = recorded[alias]
            coordinate_key = "module" if kind == "libraries" else "id"
            coordinate = source.get(coordinate_key)
            expected_coordinate = row.get("coordinate")
            if coordinate != expected_coordinate:
                errors.append(
                    f"{kind}.{alias} coordinate is {coordinate!r}, inventory says {expected_coordinate!r}"
                )
            version = resolved_version(source, versions)
            if version is None:
                errors.append(f"{kind}.{alias} has no resolvable catalog version")
            elif str(version) != str(row.get("version")):
                errors.append(
                    f"{kind}.{alias} version is {version!r}, inventory says {row.get('version')!r}"
                )

    return errors


def check_additional(root: Path, rows: list[dict]) -> list[str]:
    errors: list[str] = []
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    native_config_path = root / "native/dependencies.cmake"
    if not native_config_path.is_file():
        return ["required native/dependencies.cmake is missing"]
    native_config = native_config_path.read_text(encoding="utf-8")

    native_pins: dict[str, str] = {}
    for name in ("PROTOBUF", "ABSEIL"):
        match = re.search(rf"set\(MYNDHAMR_{name}_VERSION ([^)]+)\)", native_config)
        if not match:
            errors.append(f"native/dependencies.cmake lacks MYNDHAMR_{name}_VERSION")
        else:
            native_pins[name] = match.group(1).strip()

    for row in rows:
        name = row.get("name", "<unnamed>")
        source = row.get("source", "")
        if source == "gradle/wrapper/gradle-wrapper.properties":
            match = re.search(r"gradle-([0-9][^/\\]+)-bin\.zip", wrapper)
            if not match or match.group(1) != str(row.get("version")):
                errors.append(f"{name} version does not match gradle-wrapper.properties")
        elif source == "settings.gradle.kts":
            match = re.search(
                r'id\("org\.gradle\.toolchains\.foojay-resolver-convention"\)\s+version\s+"([^"]+)"',
                settings,
            )
            if not match or match.group(1) != str(row.get("version")):
                errors.append(f"{name} version does not match settings.gradle.kts")
        elif name == "Protobuf Java runtime":
            expected = f"4.{native_pins.get('PROTOBUF', '')}"
            if row.get("version") != expected:
                errors.append(f"{name} version must match native Protobuf pin ({expected})")
        elif name == "Protobuf C++ runtime and protoc":
            if row.get("version") != native_pins.get("PROTOBUF"):
                errors.append(f"{name} version does not match native Protobuf pin")
        elif name == "Abseil C++":
            if row.get("version") != native_pins.get("ABSEIL"):
                errors.append(f"{name} version does not match native Abseil pin")

    return errors


def check_licenses(inventory: dict) -> list[str]:
    errors: list[str] = []
    entries = []
    for group in inventory.get("catalog", {}).values():
        entries.extend(group.values())
    entries.extend(inventory.get("additional", []))
    for entry in entries:
        coordinate = entry.get("coordinate", entry.get("name", "<unnamed>"))
        license_id = entry.get("license")
        scope = entry.get("scope")
        if license_id not in KNOWN_LICENSES:
            errors.append(f"{coordinate} has unknown license {license_id!r}")
        if license_id in COPYLEFT_LICENSES and scope == "production":
            errors.append(f"copyleft dependency {coordinate} is marked production")
        if scope not in {"production", "production-transitive", "test", "development", "build", "toolchain"}:
            errors.append(f"{coordinate} has unknown scope {scope!r}")
        if entry.get("status") not in {"used", "unused", "bundled"}:
            errors.append(f"{coordinate} has unknown status {entry.get('status')!r}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (defaults to the parent of this script)",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    # Keep the inventory beside this checker even when --root points at a
    # sibling checkout used to validate repository declarations.
    inventory_path = Path(__file__).resolve().parent.parent / "docs/dependency-inventory.json"
    if not inventory_path.is_file():
        fail(f"inventory file not found: {inventory_path}")
        return 1

    try:
        inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
        errors = check_catalog(root, inventory.get("catalog", {}), inventory)
        errors.extend(check_additional(root, inventory.get("additional", [])))
        errors.extend(check_licenses(inventory))
    except (OSError, json.JSONDecodeError, tomllib.TOMLDecodeError) as error:
        fail(str(error))
        return 1

    if errors:
        for error in errors:
            fail(error)
        return 1
    print("inventory check: catalog coverage, pinned versions, and license metadata passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
