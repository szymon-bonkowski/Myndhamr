#!/usr/bin/env python3
"""Bounded, out-of-process COLMAP sparse mapper for normalized v1 scan inputs.

Run as ``python worker.py <run-directory>``. The caller owns process timeouts and
captures stdout/stderr; this worker writes its result and diagnostics into the
already-created run directory without touching the original scan.
"""

from __future__ import annotations

import json
import math
import os
import re
import statistics
import sys
import time
import traceback
from collections import defaultdict
from pathlib import Path
from typing import Any


WORKER_VERSION = "1"
PINNED_PYCOLMAP = "3.13.0"
SAFE_IMAGE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.png$")
DEFAULT_CONFIG: dict[str, Any] = {
    "threads": 4,
    "maxImageSize": 3200,
    "maxFeatures": 8192,
    "minNumMatches": 15,
    "initMinNumInliers": 30,
    "initMinTriAngleDegrees": 4.0,
    "absPoseMinNumInliers": 15,
    "seed": 0,
}


class WorkerError(RuntimeError):
    """An input, environment, or reconstruction failure with an actionable message."""


def _json_write_new(path: Path, value: Any) -> None:
    """Create a JSON artifact exclusively; never replace a prior run's output."""
    data = (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n").encode()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
    except BaseException:
        try:
            path.unlink()
        except OSError:
            pass
        raise


def _read_json(path: Path, label: str) -> Any:
    try:
        with path.open("r", encoding="utf-8") as stream:
            return json.load(stream)
    except FileNotFoundError as exc:
        raise WorkerError(f"missing required {label}: {path.name}") from exc
    except (OSError, json.JSONDecodeError) as exc:
        raise WorkerError(f"cannot read {label} {path.name}: {exc}") from exc


def _finite_number(value: Any, label: str, *, positive: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise WorkerError(f"{label} must be a finite number")
    number = float(value)
    if not math.isfinite(number) or (positive and number <= 0):
        expectation = "finite and positive" if positive else "finite"
        raise WorkerError(f"{label} must be {expectation}")
    return number


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise WorkerError(f"{label} must be a positive integer")
    return value


def _load_config(run_dir: Path) -> dict[str, Any]:
    path = run_dir / "worker-config.json"
    if not path.exists():
        raw: Any = {}
    else:
        raw = _read_json(path, "worker config")
    if not isinstance(raw, dict):
        raise WorkerError("worker-config.json must contain a JSON object")
    unknown = sorted(set(raw) - set(DEFAULT_CONFIG))
    if unknown:
        raise WorkerError(f"unknown worker config key(s): {', '.join(unknown)}")
    config = {**DEFAULT_CONFIG, **raw}
    for key in ("threads", "maxImageSize", "maxFeatures", "minNumMatches",
                "initMinNumInliers", "absPoseMinNumInliers"):
        config[key] = _positive_int(config[key], f"worker config {key}")
    config["initMinTriAngleDegrees"] = _finite_number(
        config["initMinTriAngleDegrees"], "worker config initMinTriAngleDegrees", positive=True
    )
    if isinstance(config["seed"], bool) or not isinstance(config["seed"], int) or config["seed"] < 0:
        raise WorkerError("worker config seed must be a non-negative integer")
    return config


def _validate_inputs(run_dir: Path) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    input_doc = _read_json(run_dir / "input.json", "normalized input")
    if not isinstance(input_doc, dict) or input_doc.get("schemaVersion") != 1:
        raise WorkerError("input.json must be an object with schemaVersion 1")
    raw_images = input_doc.get("images")
    if not isinstance(raw_images, list) or len(raw_images) < 2:
        raise WorkerError("input.json images must contain at least two eligible images")
    images: list[dict[str, Any]] = []
    names: set[str] = set()
    frame_ids: set[str] = set()
    for index, raw in enumerate(raw_images):
        label = f"input.json images[{index}]"
        if not isinstance(raw, dict):
            raise WorkerError(f"{label} must be an object")
        frame_id = raw.get("frameId")
        name = raw.get("name")
        if not isinstance(frame_id, str) or not frame_id:
            raise WorkerError(f"{label}.frameId must be a non-empty string")
        if frame_id in frame_ids:
            raise WorkerError(f"duplicate frameId in input.json: {frame_id}")
        frame_ids.add(frame_id)
        if not isinstance(name, str) or not SAFE_IMAGE_NAME.fullmatch(name) or Path(name).name != name or ".." in name:
            raise WorkerError(f"{label}.name must be a safe PNG filename")
        if name in names:
            raise WorkerError(f"duplicate image name in input.json: {name}")
        names.add(name)
        width = _positive_int(raw.get("width"), f"{label}.width")
        height = _positive_int(raw.get("height"), f"{label}.height")
        intrinsics = {key: _finite_number(raw.get(key), f"{label}.{key}", positive=key in ("fx", "fy"))
                      for key in ("fx", "fy", "cx", "cy")}
        matrix = raw.get("worldFromCameraColumnMajor")
        if (not isinstance(matrix, list) or len(matrix) != 16
                or any(not isinstance(v, (int, float)) or isinstance(v, bool) or not math.isfinite(float(v)) for v in matrix)):
            raise WorkerError(f"{label}.worldFromCameraColumnMajor must contain 16 finite values")
        image_path = run_dir / "images" / name
        images_root = run_dir / "images"
        if images_root.is_symlink() or not images_root.is_dir():
            raise WorkerError("images must be a real directory inside the run directory")
        if image_path.is_symlink() or not image_path.is_file():
            raise WorkerError(f"image file missing for frameId {frame_id}: images/{name}")
        if image_path.resolve().parent != images_root.resolve():
            raise WorkerError(f"image path escapes the run directory for frameId {frame_id}")
        try:
            from PIL import Image as PILImage
            with PILImage.open(image_path) as image:
                image.verify()
                if image.format != "PNG":
                    raise WorkerError(f"images/{name} is not a PNG image")
            with PILImage.open(image_path) as image:
                if image.size != (width, height):
                    raise WorkerError(
                        f"image dimensions disagree for frameId {frame_id}: "
                        f"input says {width}x{height}, PNG is {image.width}x{image.height}"
                    )
        except WorkerError:
            raise
        except Exception as exc:
            raise WorkerError(f"cannot decode images/{name} for frameId {frame_id}: {exc}") from exc
        images.append({**raw, **intrinsics, "width": width, "height": height,
                       "frameId": frame_id, "name": name, "path": image_path})

    pairs_doc = _read_json(run_dir / "pairs.json", "pair graph")
    if isinstance(pairs_doc, dict):
        raw_pairs = pairs_doc.get("pairs")
    else:
        raw_pairs = pairs_doc
    if not isinstance(raw_pairs, list) or not raw_pairs:
        raise WorkerError("pairs.json must contain a non-empty array (or object with a non-empty pairs array)")
    by_name = {image["name"] for image in images}
    pairs: list[dict[str, str]] = []
    unique_pairs: set[tuple[str, str]] = set()
    for index, raw in enumerate(raw_pairs):
        label = f"pairs.json pairs[{index}]"
        if not isinstance(raw, dict):
            raise WorkerError(f"{label} must be an object")
        first, second = raw.get("first"), raw.get("second")
        if not isinstance(first, str) or not isinstance(second, str) or first == second:
            raise WorkerError(f"{label} must name two different input images")
        if first not in by_name or second not in by_name:
            raise WorkerError(f"{label} references an image absent from input.json")
        key = tuple(sorted((first, second)))
        if key in unique_pairs:
            raise WorkerError(f"duplicate pair in pairs.json: {key[0]} / {key[1]}")
        unique_pairs.add(key)
        reason = raw.get("reason", "")
        pairs.append({"first": first, "second": second,
                      "reason": reason if isinstance(reason, str) else str(reason)})
    return images, pairs


def _component_sizes(node_names: list[str], edges: list[tuple[str, str]]) -> list[int]:
    adjacency: dict[str, set[str]] = {name: set() for name in node_names}
    for first, second in edges:
        adjacency[first].add(second)
        adjacency[second].add(first)
    sizes: list[int] = []
    unseen = set(node_names)
    while unseen:
        seed = min(unseen)
        queue = [seed]
        unseen.remove(seed)
        size = 0
        while queue:
            node = queue.pop()
            size += 1
            for neighbor in adjacency[node] & unseen:
                unseen.remove(neighbor)
                queue.append(neighbor)
        sizes.append(size)
    return sorted(sizes, reverse=True)


def _image_name_to_frame(images: list[dict[str, Any]]) -> dict[str, str]:
    return {image["name"]: image["frameId"] for image in images}


def _stats_from_database(database: Any, pycolmap: Any, images: list[dict[str, Any]], pairs: list[dict[str, str]]) -> dict[str, Any]:
    db_images = database.read_all_images()
    id_to_name = {image.image_id: image.name for image in db_images}
    verified_ids, verified_geometries = database.read_two_view_geometries()
    verified_edges: list[tuple[str, str]] = []
    verified_pair_inliers: list[int] = []
    for pair_id, geometry in zip(verified_ids, verified_geometries):
        # Use COLMAP's public conversion helper; pair IDs use its own base-
        # encoding and are not packed 32-bit halves.
        image_id1, image_id2 = pycolmap.pair_id_to_image_pair(int(pair_id))
        name1, name2 = id_to_name.get(image_id1), id_to_name.get(image_id2)
        if name1 is not None and name2 is not None:
            verified_edges.append((name1, name2))
            verified_pair_inliers.append(int(len(geometry.inlier_matches)))
    return {
        "featureExtraction": {
            "imageCount": database.num_images(),
            "keypointCount": database.num_keypoints(),
            "keypointsByImage": {
                image.name: database.num_keypoints_for_image(image.image_id)
                for image in db_images
            },
        },
        "matching": {
            "inputPairCount": len(pairs),
            "matchedPairCount": database.num_matched_image_pairs(),
            "rawMatchCount": database.num_matches(),
            "verifiedPairCount": database.num_verified_image_pairs(),
            "verifiedInlierMatchCount": database.num_inlier_matches(),
            "verifiedPairInliers": sorted(verified_pair_inliers),
        },
        "verifiedGraph": {
            "verifiedPairCount": len(verified_edges),
            "componentCount": len(_component_sizes([image["name"] for image in images], verified_edges)),
            "componentSizes": _component_sizes([image["name"] for image in images], verified_edges),
        },
    }


def _column_major_world_to_optical(image: Any) -> list[float]:
    import numpy as np

    # COLMAP exposes T_optical_world (world to optical camera) as an SE(3)
    # 3x4 block. Embed it explicitly into homogeneous 4x4 form before flattening.
    rigid = np.asarray(image.cam_from_world().matrix(), dtype=np.float64)
    if rigid.shape != (3, 4) or not np.isfinite(rigid).all():
        raise WorkerError(f"COLMAP returned unexpected world-to-optical matrix shape {rigid.shape}")
    homogeneous = np.eye(4, dtype=np.float64)
    homogeneous[:3, :4] = rigid
    return homogeneous.reshape(16, order="F").tolist()


def _export_component(reconstruction: Any, component_dir: Path, frame_by_name: dict[str, str]) -> dict[str, Any]:
    import numpy as np

    component_dir.mkdir(parents=True, exist_ok=False)
    reconstruction.write_binary(str(component_dir))
    reconstruction.write_text(str(component_dir))
    registered_ids = sorted(int(image_id) for image_id in reconstruction.reg_image_ids())
    image_to_frame: dict[int, str] = {}
    camera_rows: list[dict[str, Any]] = []
    for image_id in registered_ids:
        image = reconstruction.image(image_id)
        frame_id = frame_by_name.get(image.name)
        if frame_id is None:
            raise WorkerError(f"registered image absent from normalized input: {image.name}")
        image_to_frame[image_id] = frame_id
        camera = reconstruction.camera(image.camera_id)
        camera_model_name = getattr(camera.model, "name", str(camera.model).split(".")[-1])
        if camera_model_name != "PINHOLE":
            raise WorkerError(f"COLMAP produced unsupported camera model {camera.model} for {image.name}")
        params = [float(value) for value in camera.params]
        if len(params) != 4:
            raise WorkerError(f"PINHOLE camera for {image.name} has {len(params)} parameters, expected 4")
        camera_rows.append({
            "frameId": frame_id,
            "name": image.name,
            "cameraId": int(image.camera_id),
            "width": int(camera.width),
            "height": int(camera.height),
            "fx": params[0], "fy": params[1], "cx": params[2], "cy": params[3],
            "worldToOpticalColumnMajor": _column_major_world_to_optical(image),
        })

    points: list[dict[str, Any]] = []
    errors: list[float] = []
    observation_count = 0
    for point_id in sorted(int(value) for value in reconstruction.point3D_ids()):
        point = reconstruction.point3D(point_id)
        xyz = np.asarray(point.xyz, dtype=np.float64).reshape(-1)
        color = [int(value) for value in point.color]
        error = float(point.error)
        if xyz.shape != (3,) or not np.isfinite(xyz).all() or not math.isfinite(error):
            raise WorkerError(f"COLMAP point {point_id} contains non-finite or malformed values")
        observations: list[dict[str, Any]] = []
        for element in point.track.elements:
            image_id, point2d_index = int(element.image_id), int(element.point2D_idx)
            frame_id = image_to_frame.get(image_id)
            if frame_id is None:
                continue
            xy = np.asarray(reconstruction.image(image_id).point2D(point2d_index).xy, dtype=np.float64).reshape(-1)
            if xy.shape != (2,) or not np.isfinite(xy).all():
                raise WorkerError(f"COLMAP point {point_id} has a malformed image observation")
            observations.append({"frameId": frame_id, "u": float(xy[0]), "v": float(xy[1])})
        observations.sort(key=lambda value: value["frameId"])
        observation_count += len(observations)
        errors.append(error)
        points.append({"id": str(point_id), "position": xyz.tolist(), "color": color,
                       "errorPixels": error, "observations": observations})
    reprojection = {
        "mean": statistics.fmean(errors) if errors else None,
        "median": statistics.median(errors) if errors else None,
        "p95": float(np.percentile(np.asarray(errors), 95)) if errors else None,
    }
    return {
        "schemaVersion": 1,
        "cameras": camera_rows,
        "points": points,
        "componentCount": None,
        "componentSizes": None,
        "registeredImages": len(camera_rows),
        "sparsePointCount": len(points),
        "observationCount": observation_count,
        "reprojectionErrorPixels": reprojection,
    }


def _configure_pycolmap(pycolmap: Any, config: dict[str, Any]) -> tuple[Any, Any, Any]:
    pycolmap.set_random_seed(config["seed"])

    extraction = pycolmap.FeatureExtractionOptions()
    extraction.max_image_size = config["maxImageSize"]
    extraction.num_threads = config["threads"]
    extraction.use_gpu = False
    extraction.sift.max_num_features = config["maxFeatures"]

    verification = pycolmap.TwoViewGeometryOptions()
    verification.min_num_inliers = config["minNumMatches"]
    verification.ransac.random_seed = config["seed"]

    pipeline = pycolmap.IncrementalPipelineOptions()
    pipeline.min_num_matches = config["minNumMatches"]
    pipeline.multiple_models = True
    pipeline.max_num_models = 50
    pipeline.min_model_size = 3
    pipeline.num_threads = config["threads"]
    pipeline.random_seed = config["seed"]
    pipeline.extract_colors = True
    pipeline.ba_refine_focal_length = False
    pipeline.ba_refine_principal_point = False
    pipeline.ba_refine_extra_params = False
    pipeline.ba_use_gpu = False
    pipeline.use_prior_position = False
    pipeline.use_robust_loss_on_prior_position = False
    pipeline.mapper.init_min_num_inliers = config["initMinNumInliers"]
    pipeline.mapper.init_min_tri_angle = config["initMinTriAngleDegrees"]
    pipeline.mapper.abs_pose_min_num_inliers = config["absPoseMinNumInliers"]
    pipeline.mapper.abs_pose_refine_focal_length = False
    pipeline.mapper.abs_pose_refine_extra_params = False
    pipeline.mapper.num_threads = config["threads"]
    pipeline.mapper.random_seed = config["seed"]
    pipeline.triangulation.random_seed = config["seed"]
    return extraction, verification, pipeline


def run(run_dir_value: str) -> int:
    run_dir = Path(run_dir_value).expanduser().resolve()
    diagnostics_path = run_dir / "adapter-diagnostics.json"
    diagnostics: dict[str, Any] = {
        "status": "failed",
        "toolVersion": {"worker": WORKER_VERSION, "pycolmap": None, "numpy": None, "pillow": None},
        "config": None,
        "inputEligibleImages": 0,
        "registeredImages": 0,
        "registeredFraction": 0.0,
        "unregisteredFrameIds": [],
        "sparsePointCount": 0,
        "observationCount": 0,
        "reprojectionErrorPixels": {"mean": None, "median": None, "p95": None},
        "componentCount": 0,
        "componentSizes": [],
        "featureExtraction": {},
        "matching": {},
        "verifiedGraph": {},
        "stageRuntimeSeconds": {},
        "error": None,
    }
    started = time.monotonic()
    generated_root = run_dir / "colmap"
    try:
        if not run_dir.is_dir():
            raise WorkerError(f"run directory does not exist: {run_dir}")
        if diagnostics_path.exists():
            raise WorkerError("adapter-diagnostics.json already exists; refusing to overwrite prior output")
        if generated_root.exists():
            raise WorkerError("colmap output already exists; refusing to overwrite prior output")
        config = _load_config(run_dir)
        diagnostics["config"] = config
        images, pairs = _validate_inputs(run_dir)
        diagnostics["inputEligibleImages"] = len(images)
        diagnostics["unregisteredFrameIds"] = [image["frameId"] for image in images]

        # Limit common native thread pools before importing COLMAP. verify_matches
        # below has an API limitation documented in tool metadata and diagnostics.
        for variable in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "NUMEXPR_NUM_THREADS"):
            os.environ[variable] = str(config["threads"])
        import pycolmap

        diagnostics["toolVersion"]["pycolmap"] = str(pycolmap.__version__)
        if str(pycolmap.__version__) != PINNED_PYCOLMAP:
            raise WorkerError(f"pycolmap version must be exactly {PINNED_PYCOLMAP}; found {pycolmap.__version__}")
        import numpy
        import PIL
        diagnostics["toolVersion"]["numpy"] = str(numpy.__version__)
        diagnostics["toolVersion"]["pillow"] = str(PIL.__version__)
        if not hasattr(pycolmap, "set_random_seed"):
            raise WorkerError("installed pycolmap lacks set_random_seed")
        extraction, verification, pipeline = _configure_pycolmap(pycolmap, config)
        generated_root.mkdir(parents=False, exist_ok=False)
        database_path = generated_root / "database.db"
        image_root = run_dir / "images"
        # pycolmap.import_images requires the database file to exist and opens it
        # as the COLMAP SQLite schema. Database.open initializes an empty file.
        bootstrap_database = pycolmap.Database.open(str(database_path))
        bootstrap_database.close()
        by_calibration: dict[tuple[int, int, float, float, float, float], list[str]] = defaultdict(list)
        for image in images:
            key = (image["width"], image["height"], image["fx"], image["fy"], image["cx"], image["cy"])
            by_calibration[key].append(image["name"])

        stage_start = time.monotonic()
        for (width, height, fx, fy, cx, cy), image_names in sorted(by_calibration.items(), key=lambda item: item[0]):
            reader = pycolmap.ImageReaderOptions()
            reader.camera_model = "PINHOLE"
            reader.camera_params = ",".join(str(value) for value in (fx, fy, cx, cy))
            pycolmap.import_images(
                str(database_path), str(image_root), camera_mode=pycolmap.CameraMode.SINGLE,
                image_names=sorted(image_names), options=reader,
            )
        diagnostics["stageRuntimeSeconds"]["imageImport"] = time.monotonic() - stage_start

        stage_start = time.monotonic()
        pycolmap.extract_features(
            str(database_path), str(image_root), image_names=[image["name"] for image in images],
            reader_options=pycolmap.ImageReaderOptions(), extraction_options=extraction,
            device=pycolmap.Device.cpu,
        )
        diagnostics["stageRuntimeSeconds"]["featureExtraction"] = time.monotonic() - stage_start

        pairs_path = generated_root / "pairs.txt"
        with pairs_path.open("x", encoding="utf-8", newline="\n") as stream:
            for pair in pairs:
                stream.write(f"{pair['first']} {pair['second']}\n")
        # pycolmap 3.13 VerifyMatches internally constructs FeatureMatchingOptions,
        # forces CPU matching, and exposes no argument for overriding it. Record
        # the compiled defaults instead of pretending the threads config applies.
        matching_defaults = pycolmap.FeatureMatchingOptions().todict()
        diagnostics["toolMetadata"] = {
            "verifyMatchesApi": "pycolmap.verify_matches(database_path, pairs_path, options)",
            "matchingOptions": matching_defaults,
            "matchingOptionsConstraint": (
                "verify_matches creates compiled FeatureMatchingOptions internally, forces CPU, "
                "and does not expose matching_options; its compiled num_threads default is recorded above. "
                "The worker threads setting applies to feature extraction and mapping, not this API call."
            ),
            "cameraConvention": "COLMAP optical camera: +X right, +Y down, +Z forward; T_optical_world is exported column-major.",
            "priorUsage": "worldFromCameraColumnMajor is validated but never installed as a COLMAP pose constraint; visual poses remain unconstrained.",
        }
        stage_start = time.monotonic()
        pycolmap.verify_matches(str(database_path), str(pairs_path), verification)
        diagnostics["stageRuntimeSeconds"]["featureMatchingAndVerification"] = time.monotonic() - stage_start

        database = pycolmap.Database.open(str(database_path))
        try:
            diagnostics.update(_stats_from_database(database, pycolmap, images, pairs))
        finally:
            database.close()

        stage_start = time.monotonic()
        mapping_root = generated_root / "mapper-output"
        reconstructions = pycolmap.incremental_mapping(
            str(database_path), str(image_root), str(mapping_root), options=pipeline
        )
        diagnostics["stageRuntimeSeconds"]["incrementalMapping"] = time.monotonic() - stage_start

        if not reconstructions:
            raise WorkerError("COLMAP produced no registered sparse model; inspect pair verification and feature diagnostics")
        components = sorted(
            ((int(component_id), reconstruction) for component_id, reconstruction in reconstructions.items()),
            key=lambda item: (-int(item[1].num_reg_images()), item[0]),
        )
        diagnostics["componentCount"] = len(components)
        diagnostics["componentSizes"] = [int(reconstruction.num_reg_images()) for _, reconstruction in components]
        models_root = generated_root / "models"
        models_root.mkdir(parents=False, exist_ok=False)
        frame_by_name = _image_name_to_frame(images)
        component_payloads: list[dict[str, Any]] = []
        largest_model_path: Path | None = None
        stage_start = time.monotonic()
        for ordinal, (component_id, reconstruction) in enumerate(components, 1):
            model_path = models_root / f"component-{ordinal:03d}"
            payload = _export_component(reconstruction, model_path, frame_by_name)
            payload["componentCount"] = len(components)
            payload["componentSizes"] = diagnostics["componentSizes"]
            component_payloads.append(payload)
            if ordinal == 1:
                largest_model_path = model_path
        diagnostics["stageRuntimeSeconds"]["modelExport"] = time.monotonic() - stage_start
        if largest_model_path is None or not component_payloads:
            raise WorkerError("COLMAP returned an empty set of sparse components")

        largest = component_payloads[0]
        normalized_path = generated_root / "raw-model.json"
        _json_write_new(normalized_path, {
            "schemaVersion": 1,
            "cameras": largest["cameras"],
            "points": largest["points"],
            "componentCount": len(components),
            "componentSizes": diagnostics["componentSizes"],
        })
        registered_frame_ids = {camera["frameId"] for camera in largest["cameras"]}
        diagnostics["registeredImages"] = len(registered_frame_ids)
        diagnostics["registeredFraction"] = len(registered_frame_ids) / len(images)
        diagnostics["unregisteredFrameIds"] = sorted(
            image["frameId"] for image in images if image["frameId"] not in registered_frame_ids
        )
        diagnostics["sparsePointCount"] = largest["sparsePointCount"]
        diagnostics["observationCount"] = largest["observationCount"]
        diagnostics["reprojectionErrorPixels"] = largest["reprojectionErrorPixels"]
        diagnostics["status"] = "succeeded"
        diagnostics["error"] = None
        diagnostics["stageRuntimeSeconds"]["total"] = time.monotonic() - started
        _json_write_new(diagnostics_path, diagnostics)
        print(f"COLMAP sparse reconstruction succeeded: {len(registered_frame_ids)}/{len(images)} images, "
              f"{largest['sparsePointCount']} points, {len(components)} component(s)")
        return 0
    except Exception as exc:
        diagnostics["status"] = "failed"
        diagnostics["error"] = {
            "type": type(exc).__name__,
            "message": str(exc) or repr(exc),
        }
        diagnostics["stageRuntimeSeconds"]["total"] = time.monotonic() - started
        print(f"COLMAP sparse reconstruction failed: {diagnostics['error']['message']}", file=sys.stderr)
        if not isinstance(exc, WorkerError):
            traceback.print_exc(file=sys.stderr)
        if run_dir.is_dir() and not diagnostics_path.exists():
            try:
                _json_write_new(diagnostics_path, diagnostics)
            except OSError as diag_exc:
                print(f"could not write adapter diagnostics: {diag_exc}", file=sys.stderr)
        return 1


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print("usage: python worker.py <run-directory>", file=sys.stderr)
        return 2
    return run(args[0])


if __name__ == "__main__":
    raise SystemExit(main())
