# v0.3 metric object reconstruction

The object workflow keeps capture files and v0.2 sparse products immutable. Dense output is additional versioned derived data. No texture baking, smoothing, size normalization, largest-component selection or hole filling occurs.

## Setup

From the repository root, build the existing native alignment/desktop CLI and install the isolated worker environments:

```sh
./gradlew nativeBuild :desktopApp:installDist
python3 -m venv build/v02-venv
build/v02-venv/bin/python -m pip install -r desktop-workers/colmap-adapter/requirements.txt
python3 -m venv build/v03-venv
build/v03-venv/bin/python -m pip install -r desktop-workers/object-mesh/requirements-cuda.txt -r desktop-workers/object-mesh/requirements-test.txt
```

The default sparse environment remains COLMAP3.13.0. Dense uses COLMAP4.2.1 with the upstream RTX50/Blackwell PatchMatch workaround. CUDA dense reconstruction requires a supported NVIDIA GPU and driver; the worker loads separately installed CUDA runtime libraries. Missing CUDA fails explicitly. Open3D0.20.0 supplies native surface processing. Heavy local acceptance does not run in CPU-only CI.

## Single-command workflow

```sh
desktopApp/build/install/desktopApp/bin/desktopApp object /absolute/capture.scan3d build/object-run
```

This validates ingest, creates a sibling `object-run-sparse`, reconstructs/alines sparse cameras, estimates stereo depth and fuses dense observations, maps them into capture-world metres, generates a conservative mesh and exports all formats. Outputs live outside the raw scan. A directory or capture ZIP is accepted. Paths may contain spaces when quoted.

To reuse an existing accepted v0.2 sparse result:

```sh
desktopApp/build/install/desktopApp/bin/desktopApp object /absolute/capture.scan3d build/object-run --sparse /absolute/sparse-run
```

The CLI validates the capture and compares its manifest hash with the sparse source identity. The worker checks connected metric registration, image/calibration presence, native cameras versus aligned cameras/points and proper Sim(3). All input files, configuration, engine versions and code enter a checkpoint fingerprint. Completed stage outputs have SHA256 hashes; unchanged retries reuse them. Incomplete stages retry without being marked successful. Changed inputs/configuration/worker/backend or corrupt checkpoint outputs require a new run directory. Geometry/export-module changes are tracked per stage: compatible dense transforms reuse the dense checkpoint, stale downstream products are archived and recomputed. Transform helper implementations participate in dense compatibility; validation refuses stale dependent stages. Retrying the full command automatically reuses its existing sibling sparse result.

## Explicit stages and exports

```sh
desktopApp/build/install/desktopApp/bin/desktopApp dense /absolute/sparse-run build/object-run
desktopApp/build/install/desktopApp/bin/desktopApp mesh /absolute/sparse-run build/object-run
desktopApp/build/install/desktopApp/bin/desktopApp export /absolute/sparse-run build/object-run
desktopApp/build/install/desktopApp/bin/desktopApp mesh-validate build/object-run
```

Select requested exports at the start with `--formats ply`, `--formats obj`, `--formats glb` or `--formats ply,obj,glb`. Keep the same options on explicit subsequent stages. The geometry is the same in each file; PLY and OBJ store doubles, GLB stores float32 positions/normals and uint32 indices. GLB has identity nodes with no hidden scale or axis compensation. World coordinates are right-handed +Y up, metres, without recentering. OBJ/PLY comments and glTF extras document units/frame.

Defaults are explicit: `--max-image-size 1600`, `--radii-meters 0.005,0.01`, `--max-edge-meters 0.02`, `--min-component-faces 0`, `--timeout-seconds 3600`. These are resource/geometric profile settings, not claimed physical accuracy. All registered images are used; there is no photo-count cap. PatchMatch uses geometric consistency/filtering and a2GB cache; fusion uses four threads and five-pixel support. Ball pivoting creates local observed-point triangles, rejects overlong edges and does not close unsupported regions. Exact duplicate vertices/faces, zero-area faces and unreferenced vertices are removed. Near-degenerate geometry remains diagnosed without arbitrary removal. Same-direction shared-edge faces are isolated at explicit topological seams, and disconnected vertex fans are split: all triangle corners, positions, winding and area stay unchanged. Intentional seam vertices are never welded afterward. Original affected edges/faces and orientation uncertainty are recorded; no global flipping replaces locally measured normals. All disconnected components are retained by default. An explicitly requested component face threshold logs each component/area/bounds and deletion reason; there is no default cosmetic debris removal.

Vertex normals are area-weighted from observed winding, finite and unit-normalized. Undefined normals fail. Boundaries, winding conflicts, nonmanifold edges, degenerate/duplicate geometry, components, area, world bounds and dimensions remain visible. Nonmanifold edges/winding-conflict output fails the pipeline sanity gate. Vertex manifoldness is reported and required by independent acceptance. Open boundaries are allowed. Bounds are checked against registered camera baseline to detect collapse/absurd scale, not to invent object dimensions.

## Diagnostics and inspection

Read `object-manifest.json`, `diagnostics.json`, `mesh-diagnostics.json` and `object-all.log` (or stage log). The manifest records source reconstruction ID/hashes, versions/options/config, runtime and output hashes. Dense workspace/checkpoints/logs survive failure; a failed stage cannot become a cached success. `dense.npz`, `dense-metric.ply` and `mesh.npz` preserve full measured/derived geometry; `exports/mesh.ply`, `mesh.obj`, `mesh.glb` are final results.

Open `inspection.html` directly in a browser for offline shaded/wire mesh inspection with yaw/pitch/zoom and metric dimensions. Its deterministic display sampling is limited to30,000 triangles and never alters full exports. Display centering affects only the view, never stored coordinates. Background captured geometry remains present; segmentation is outside v0.3.

## Validation and benchmarks

```sh
tools/validate.sh
# Object-only deterministic tests and bounded benchmark:
tools/validate-object.sh
# Reuse already installed CUDA environment for local object tests:
MYNDHAMR_OBJECT_PYTHON="$PWD/build/v03-venv/bin/python" tools/validate-object.sh
build/v03-venv/bin/python tools/check-object-acceptance.py build/object-run
# Optional independent Khronos schema/accessor validator used in acceptance:
npm install --prefix build/gltf-validation gltf-validator@2.0.0-dev.3.10
node tools/validate-glb.cjs build/object-run/exports/mesh.glb
```

Normal CI executes synthetic known-answer geometry, actual Open3D surface fixtures, adapter/checkpoint failures and independent Open3D/trimesh export reads, plus all existing suites. It does not count mocked stage orchestration as actual stereo acceptance. The benchmark measures validation, cleanup/normals, binary loading and all exporters for a12,800-triangle plane, checking known area and counts.

Heavy controlled acceptance uses the fixture from `tools/validate-sparse.sh`:

```sh
desktopApp/build/install/desktopApp/bin/desktopApp object build/sparse-acceptance-XXXXXX/fixture.scan3d build/controlled-object --sparse build/sparse-acceptance-XXXXXX/run
build/v03-venv/bin/python tools/check-object-acceptance.py build/controlled-object --controlled
```

The independent three-plane geometry at z=2.8/3.2/4m must retain its >1m depth extent, median point-to-plane residual<5mm and p95<20mm. These are synthetic fixture tolerances. Full exports must match internal vertices/faces/normals; GLB rounding tolerance scales with coordinate magnitude.

Real acceptance reuses the existing68-view backpack scan:

```sh
desktopApp/build/install/desktopApp/bin/desktopApp object build/acceptance/v02-real-capture-20261002/controlled-capture/data/scans/10ff175e-1ee9-4583-ac88-ce95c700c790.scan3d build/real-object --sparse build/acceptance/v02-real-capture-20261002/controlled-capture/final-run
build/v03-venv/bin/python tools/check-object-acceptance.py build/real-object
```

Record counts, dimensions, topology, cleanup, stage timings, process RSS and bytes; compare raw/sparse pre/post hashes. Inspect object/floor structure and orientation numerically and with the offline viewer. AR depth/sparse agreement remains correlated sensor consistency; no unmeasured object size is ground truth. See the completed v0.3 ExecPlan for exact results and CI revision.
