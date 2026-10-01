# Dependency and license inventory

This is a checked inventory of direct external dependencies declared in the Gradle version catalog, the native CMake foundation, and the Gradle wrapper. It is not a complete transitive dependency or distribution-license audit. Before shipping, review the resolved Android, JVM, native, and platform dependency graphs and include required notices for the exact artifacts being distributed.

The machine-readable source is [`dependency-inventory.json`](dependency-inventory.json). Run `python3 tools/check-inventory.py` at the repository root to confirm that every version-catalog alias is recorded, catalog versions match, native Protobuf/Abseil pins match, and no dependency has an unknown license or a copyleft license in production scope. Add or change an inventory row in the same change that adds or changes the dependency.

## Direct dependencies

Gradle libraries and plugins are listed individually in the JSON file, including declarations that are currently unused. In-use production libraries and build plugins are Apache-2.0 except Protobuf, which is BSD-3-Clause. JUnit 4 is test-only and EPL-1.0. Unused catalog entries do not enter a build unless a build script references them; remove them when they are no longer intended to be available.

The native foundation fetches Protobuf C++/`protoc` 36.2 and Abseil 20250512.1 using SHA-256-pinned archives. The JVM Protobuf runtime is 4.36.2, derived from the same Protobuf version pin. Protobuf's bundled `utf8_range` code is MIT-licensed. Abseil is Apache-2.0. These license claims are linked to upstream license files in the JSON inventory. The production dependency list contains no OpenMVS dependency; OpenMVS is AGPL-3.0 and is not a default production dependency for this project.

The foundation's main declared licenses are permissive, but that is not a claim that every resolved transitive artifact, platform SDK component, or redistributed binary has been audited. Generate resolved reports when reviewing a build, for example:

```sh
./gradlew :androidApp:dependencies
./gradlew :shared:scan-format:dependencies
./gradlew :desktopApp:dependencies
```

Review native FetchContent contents and Android/iOS platform dependencies separately; the checker intentionally does not resolve transitive artifacts or infer licenses from artifact coordinates.
