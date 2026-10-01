# v0.2 desktop sparse reconstruction

The CLI validates immutable v1 capture evidence and reconstructs only stored manual keyframes. Ingest writes decoded lossless PNGs, source hashes/calibration/timestamps, depth evidence and a fixed list of exclusions to a new directory outside the scan. I420 images use exact luma for sparse matching; their chroma remains in raw source evidence. No automatic keyframe selection or dense reconstruction is performed.

## Setup and commands

Run from the repository root (or pass `--repository /absolute/repo`).

```sh
python3 -m venv build/v02-venv
build/v02-venv/bin/pip install -r desktop-workers/colmap-adapter/requirements.txt
./gradlew nativeBuild :desktopApp:installDist

desktopApp/build/install/desktopApp/bin/desktopApp validate /path/scan.scan3d
desktopApp/build/install/desktopApp/bin/desktopApp ingest /path/scan.scan3d /path/new-ingest-run
desktopApp/build/install/desktopApp/bin/desktopApp reconstruct /path/scan.scan3d /path/new-sparse-run
# A ZIP .scan3d/.scan3d.zip package may replace the input directory.
# Diagnostics: /path/new-sparse-run/diagnostics.json
# Open /path/new-sparse-run/trajectory.html in a browser.
```

`--python /path/python` or MYNDHAMR_SPARSE_PYTHON selects the runtime. `--timeout-seconds 600` bounds each external process. `--pose-threshold-meters 0.05` changes the recorded prior-consistency policy. Graph options are `--temporal-window 5`, `--loop-radius-meters 2`, `--max-loop-neighbors 12`, `--view-angle-degrees 75`; `--init-min-tri-angle-degrees 4` sets the recorded initialization angle. Broader graph settings for a small diagnostic scan do not justify quadratic matching on large scans. All output destinations must be new. Rerun from original evidence using the recorded options and explicit graph/config files; do not repair the COLMAP database manually.

## Contracts and diagnostics

Eligible means a persisted manual keyframe with validated tracking/pose, recognized calibration and a decodable image of the calibrated size. Corrupt projects fail full validation before reconstruction. Non-keyframes retain exclusion reasons; failed COLMAP registration never changes eligibility. Registration is the largest connected reconstruction's registered eligible images divided by the original eligible count. Separate component sizes and unregistered IDs remain available.

Pairing uses sequence neighbors plus bounded spatial/view candidates, with a deterministic32-representative reservoir per2m cell and12 loop neighbors. Pair counts and candidate-work statistics are exported. Matching and BA remain native COLMAP. Camera intrinsics remain fixed to measured CPU-image calibration. No perfect-pose initialization is substituted for visual reconstruction.

`T_world_camera` is right-handed column-major with metres and AR right/up/back camera axes. Optical camera axes are right/down/forward; D=diag(1,-1,-1). COLMAP exports `T_optical_sfm`; `C_sfm=-R^T t`. `p_world=sR_world_sfm p_sfm+t_world_sfm`, while optical camera orientation uses R without scale. Raw, capture and aligned trajectories are distinct. The HTML view compares capture/aligned in common metric axes and shows raw SfM in a separate arbitrary-unit panel. Numerical per-frame position/orientation residuals are in JSON.

Native alignment uses deterministic robust sampling plus SVD refit; both source and target consensus must be noncollinear. Defaults require60% inliers,5cm prior residual threshold and5cm metric trajectory radius. Median inlier orientation disagreement above20degrees fails. These checks assess internal consistency, not absolute accuracy. Depth sanity results retain original clock labels/source/calibration/confidence and explicitly documented provider association policies. Reused depth with a different source timestamp is skipped. See ADR-0003.

Each run contains input.json, pairs.json, worker-config.json, metric-config.json, orchestration.json, images/, evidence/, colmap/database.db, colmap/models/ (all binary/text components), colmap/raw-model.json, logs, diagnostics.json, aligned-model.json, trajectory.json/HTML, and sparse-metric.ply. Metric failures retain raw sparse evidence and logs but do not produce an apparently successful metric scene. Nonzero CLI exit indicates failure; a metric-aligned result can still have registrationAcceptancePassed=false and must not be called accepted.

COLMAP3.13.0 imported-pair matching uses compiled CPU FeatureMatchingOptions; this API does not expose a thread-count override. The worker records its complete defaults. Extraction and mapping use four configured threads. Seeds/config are reproducible; multi-threaded native scheduling does not promise bit-identical binary SfM output.

## Controlled and real validation

```sh
tools/validate-sparse.sh                  # pinned runtime, unit/negative tests, actual COLMAP known-scale fixture
./gradlew nativeTest :desktopApp:test     # numerical and deterministic Kotlin checks
./gradlew :desktopApp:sparseBenchmark    #1k/10k graph scaling and correctness counters
tools/validate.sh                         # full v0.0–v0.2 CI-equivalent validation
python3 tools/check-sparse-acceptance.py /path/real-run
```

Synthetic fixture details/tolerances are in tests/fixtures/sparse/README.md. The real v0.1 long moving capture contains31 valid manual keyframes over a15-minute room traversal. Its failures are recorded in the active ExecPlan, including the unchanged denominator, disconnected overlap, initialization experiments and unavailable trustworthy metric consensus. The other moving capture has only2 keyframes; the remaining local captures have none. Capture lifecycle acceptance does not establish a photogrammetry reference dataset. A real reconstruction gate remains pending until a suitable overlapping capture satisfies>95% registration and metric consistency. No raw capture was edited or retrospectively excluded to pass.
