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

## v0.2 desktop dependencies

Desktop geometry adds SHA-256-pinned Eigen3.4.0. Only stock Core/SVD headers are used, with EIGEN_MPL2_ONLY; no Eigen files are modified and no LGPL-only Eigen components are enabled. MPL-2.0 is file-level copyleft and permits combination with the proprietary application; preserve its source/license/notices when redistributing the headers or relevant binaries. It is audited here as a specific compatible dependency, not treated as a permissive license.

The process adapter pins pycolmap3.13.0 (COLMAP BSD-3-Clause), NumPy2.4.6 (BSD-3-Clause) and Pillow11.3.0 (MIT-CMU). Pillow validates images and renders development fixtures. These are separately installed worker dependencies, not Android dependencies. COLMAP's own license explicitly distinguishes its dependencies; prebuilt wheel contents and transitive-library notices require a distribution audit before bundling a release. No release or redistributed worker bundle is created by this milestone. License sources are recorded in dependency-inventory.json and ADR-0003.

## v0.3 object dependencies

The dense worker uses a separate pinned pycolmap-cuda12 4.2.1 environment; the sparse worker stays at 3.13.0. Version 4.2.1 includes the [upstream Blackwell PatchMatch fix](https://github.com/colmap/colmap/pull/4213), verified on the local RTX5070. COLMAP remains BSD-3-Clause; CUDA runtime/cuRAND are separately installed NVIDIA components with NVIDIA redistribution terms, not bundled by this repository. CPU CI pins pycolmap4.2.1 for adapter tests, without pretending to run CUDA stereo.

Open3D0.20.0 is [MIT](https://github.com/isl-org/Open3D/blob/v0.20.0/LICENSE); its native ball-pivot implementation supplies geometry processing through an explicit worker boundary. NumPy/Pillow retain existing pins/licenses; trimesh4.7.4 is MIT and test-only for independent export reads. These direct dependencies are compatible with the production policy and introduce no OpenMVS. Optional visualization/Python dependencies and prebuilt native wheel contents require their exact distribution notices when packaging a release; the milestone only installs separate development workers and publishes no bundle. Open3D's bundled third-party components remain under their original notices, including Eigen MPL and numerical/CUDA runtimes; do not infer a blanket MIT license for redistributed wheels from Open3D's own license.
