"""CPU acceptance tests for the versioned object-mesh worker and checkpoints."""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest import mock
import zlib

import numpy as np
import open3d as o3d
import trimesh


OBJECT_MESH = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(OBJECT_MESH))
WORKER_PATH = OBJECT_MESH / "worker.py"
_SPEC = importlib.util.spec_from_file_location("object_mesh_worker", WORKER_PATH)
assert _SPEC is not None and _SPEC.loader is not None
WORKER = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(WORKER)


_CENTERS = ((0.0, 0.0, 0.0), (0.5, 0.0, 0.0), (0.0, 0.5, 0.0))
_NAMES = tuple(f"view-{index}.png" for index in range(3))
_SOURCE_MANIFEST = hashlib.sha256(b"synthetic raw capture manifest").hexdigest()


def _png_bytes(width: int, height: int) -> bytes:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    rows = b"".join(b"\0" + bytes(width * 3) for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def _column_major_pose(center):
    pose = np.eye(4)
    pose[:3, 3] = center
    return pose.flatten(order="F").tolist()


def _write_sparse_fixture(root: Path, *, model_version: int = 1, units: str = "meters",
                          frame: str = "capture-world", status: str = "metric_aligned",
                          registered_count: int = 3, omit_image: str | None = None,
                          camera_name_override: str | None = None) -> Path:
    """Create a real COLMAP text model, convert it to binary with pycolmap, and pair it with metric JSON."""
    import pycolmap

    sparse = root / "sparse"
    image_dir = sparse / "images"
    model_dir = sparse / "colmap" / "models" / "component-001"
    text_model = root / "colmap-text"
    image_dir.mkdir(parents=True)
    text_model.mkdir(parents=True)
    model_dir.mkdir(parents=True)

    (text_model / "cameras.txt").write_text(
        "# CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n"
        "1 PINHOLE 640 480 500 500 320 240\n", encoding="ascii")
    image_records = []
    with (text_model / "images.txt").open("w", encoding="ascii") as output:
        output.write("# IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n")
        for image_id, (name, center) in enumerate(zip(_NAMES, _CENTERS), 1):
            tx, ty, tz = (-value for value in center)
            output.write(f"{image_id} 1 0 0 0 {tx} {ty} {tz} 1 {name}\n")
            output.write("320 240 1\n")
            if image_id <= registered_count:
                image_records.append({
                    "name": name, "frameId": f"frame-{image_id}", "width": 640, "height": 480,
                    "fx": 500.0, "fy": 500.0, "cx": 320.0, "cy": 240.0,
                    "worldFromCameraColumnMajor": _column_major_pose(center),
                })
            (image_dir / name).write_bytes(_png_bytes(640, 480))
    (text_model / "points3D.txt").write_text(
        "# POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[]\n"
        "1 0 0 4 255 40 20 0.1 1 0 2 0 3 0\n", encoding="ascii")

    # This is an actual text -> binary conversion through the pinned pycolmap
    # runtime; check_native_sparse reads the resulting .bin files independently.
    reconstruction = pycolmap.Reconstruction()
    reconstruction.read_text(str(text_model))
    reconstruction.write_binary(str(model_dir))
    (sparse / "colmap" / "raw-model.json").write_text(json.dumps({
        "points": [{"id": 1, "position": [0.0, 0.0, 4.0]}],
    }), encoding="utf-8")

    aligned_cameras = []
    for image in image_records:
        name = camera_name_override if camera_name_override and image["name"] == _NAMES[0] else image["name"]
        aligned_cameras.append({
            "name": name, "frameId": image["frameId"], "width": 640, "height": 480,
            "fx": 500.0, "fy": 500.0,
            "cx": 320.0, "cy": 240.0, "worldFromOpticalColumnMajor": image["worldFromCameraColumnMajor"],
        })
    model = {
        "schemaVersion": model_version, "units": units, "frame": frame,
        "alignment": {
            "scale": 1.0,
            "rotationWorldSfmRowMajor": np.eye(3).ravel().tolist(),
            "translationWorldSfmMeters": [0.0, 0.0, 0.0],
        },
        "cameras": aligned_cameras,
        "points": [{"id": 1, "position": [0.0, 0.0, 4.0]}],
    }
    (sparse / "aligned-model.json").write_text(json.dumps(model), encoding="utf-8")
    input_images = [image for image in image_records if image["name"] != omit_image]
    (sparse / "input.json").write_text(json.dumps({
        "schemaVersion": 1, "sourceManifestSha256": _SOURCE_MANIFEST, "images": input_images,
    }), encoding="utf-8")
    (sparse / "diagnostics.json").write_text(json.dumps({
        "status": status, "registrationAcceptancePassed": status == "metric_aligned",
    }), encoding="utf-8")
    return sparse


def _plane_cloud():
    x = np.linspace(2.0, 2.30, 13)
    y = np.linspace(-0.15, 0.15, 13)
    xx, yy = np.meshgrid(x, y)
    points = np.column_stack((xx.ravel(), yy.ravel(), np.full(xx.size, 4.0)))
    normals = np.tile([0.0, 0.0, 1.0], (len(points), 1))
    return points, normals


def _mock_dense_stage(sparse, run, aligned, model_path, config):
    points, normals = _plane_cloud()
    np.savez(run / "dense.npz", points=points, normals=normals)
    cloud = o3d.geometry.PointCloud()
    cloud.points = o3d.utility.Vector3dVector(points)
    cloud.normals = o3d.utility.Vector3dVector(normals)
    if not o3d.io.write_point_cloud(str(run / "dense-metric.ply"), cloud):
        raise AssertionError("Open3D failed to write the analytic dense fixture")
    return {
        "registeredImages": len(aligned["cameras"]), "densePoints": len(points),
        "boundsMeters": [points.min(axis=0).tolist(), points.max(axis=0).tolist()],
        "dimensionsMeters": np.ptp(points, axis=0).tolist(),
        "stageRuntimeSeconds": {"mockedCpuFixture": 0.0},
        "normalPolicy": "analytic +Z normals for deterministic CPU test fixture",
    }


def _config(**overrides):
    return {"schemaVersion": 1, "radiiMeters": [0.05, 0.1], "maxEdgeMeters": 0.08,
            "formats": ["ply", "obj", "glb"], **overrides}


def _json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def _load_pipeline(fixture_root: Path, run: Path, config=None, *, stage="all", dense=None):
    callback = dense or _mock_dense_stage
    with mock.patch.object(WORKER, "dense_stage", side_effect=callback) as dense_mock:
        diagnostic = WORKER.run_pipeline(fixture_root, run, _config() if config is None else config, stage)
    return diagnostic, dense_mock


class SparseWorkerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sparse = _write_sparse_fixture(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def test_sparse_input_hashes_and_pycolmap_binary_model_are_checked(self):
        model, inputs, model_path, hashes = WORKER.sparse_inputs(self.sparse, _config())
        self.assertEqual(model["units"], "meters")
        self.assertEqual(inputs["sourceManifestSha256"], _SOURCE_MANIFEST)
        self.assertEqual(len(model["cameras"]), 3)
        self.assertIn("colmap/models/component-001/images.bin", hashes)
        self.assertIn("colmap/models/component-001/cameras.bin", hashes)
        self.assertIn("colmap/models/component-001/points3D.bin", hashes)
        native = WORKER.check_native_sparse(self.sparse, model, model_path)
        self.assertEqual(native.num_images(), 3)
        self.assertEqual(native.num_points3D(), 1)
        self.assertEqual({image.name for image in native.images.values()}, set(_NAMES))

    def test_native_camera_dimensions_and_aligned_pose_homogeneous_row_are_checked(self):
        import pycolmap

        with tempfile.TemporaryDirectory() as subdir:
            sparse = _write_sparse_fixture(Path(subdir))
            aligned = WORKER.read_json(sparse / "aligned-model.json")
            model_path = sparse / "colmap" / "models" / "component-001"
            native = pycolmap.Reconstruction(str(model_path))
            native.cameras[1].width = 800
            native.write_binary(str(model_path))
            with self.assertRaisesRegex(ValueError, "model/dimensions disagreement"):
                WORKER.check_native_sparse(sparse, aligned, model_path)

        with tempfile.TemporaryDirectory() as subdir:
            sparse = _write_sparse_fixture(Path(subdir))
            aligned = WORKER.read_json(sparse / "aligned-model.json")
            pose = np.asarray(aligned["cameras"][0]["worldFromOpticalColumnMajor"])
            pose[3] = 0.25  # Column-major element (row 3, column 0) breaks homogeneity.
            aligned["cameras"][0]["worldFromOpticalColumnMajor"] = pose.tolist()
            model_path = sparse / "colmap" / "models" / "component-001"
            with self.assertRaisesRegex(ValueError, "Malformed aligned optical pose"):
                WORKER.check_native_sparse(sparse, aligned, model_path)

    def test_sanity_rejects_collapsed_scaled_and_invalid_meshes_but_keeps_small_components(self):
        from mesh_geometry import validate_mesh

        aligned = WORKER.read_json(self.sparse / "aligned-model.json")
        baseline = np.sqrt(0.5 ** 2 + 0.5 ** 2)
        box_vertices = np.array([
            [0., 0., 0.], [2., 0., 0.], [2., 3., 0.], [0., 3., 0.],
            [0., 0., 4.], [2., 0., 4.], [2., 3., 4.], [0., 3., 4.],
        ])
        box_faces = np.array([
            [0, 2, 1], [0, 3, 2], [4, 5, 6], [4, 6, 7],
            [0, 1, 5], [0, 5, 4], [1, 2, 6], [1, 6, 5],
            [2, 3, 7], [2, 7, 6], [3, 0, 4], [3, 4, 7],
        ])
        valid = validate_mesh(box_vertices, box_faces)
        accepted = WORKER.sanity(valid, aligned)
        self.assertTrue(accepted["passed"])
        self.assertAlmostEqual(accepted["cameraBaselineMeters"], baseline)

        collapsed = validate_mesh(box_vertices * 1e-8, box_faces)
        with self.assertRaisesRegex(ValueError, "Collapsed or absurd"):
            WORKER.sanity(collapsed, aligned)
        oversized = validate_mesh(box_vertices * 1000.0, box_faces)
        with self.assertRaisesRegex(ValueError, "Collapsed or absurd"):
            WORKER.sanity(oversized, aligned)

        plane_vertices = np.array([[0., 0., 0.], [2., 0., 0.], [2., 3., 0.], [0., 3., 0.]])
        plane_faces = np.array([[0, 1, 2], [0, 2, 3]])
        winding_conflict = validate_mesh(plane_vertices, np.vstack((plane_faces[0], plane_faces[1, ::-1])))
        with self.assertRaisesRegex(ValueError, "Invalid mesh topology"):
            WORKER.sanity(winding_conflict, aligned)
        nonmanifold_vertices = np.vstack((plane_vertices, [[1., 1., 1.]]))
        nonmanifold = validate_mesh(nonmanifold_vertices, np.array([[0, 1, 2], [1, 0, 3], [0, 1, 4]]))
        with self.assertRaisesRegex(ValueError, "Invalid mesh topology"):
            WORKER.sanity(nonmanifold, aligned)

        # 3 valid disconnected triangles remain accepted below the explicit
        # 100-face fragmentation gate; no implicit component pruning is applied.
        scattered = np.array([
            [0., 0., 0.], [0.2, 0., 0.], [0., 0.2, 0.],
            [0.5, 0., 0.], [0.7, 0., 0.], [0.5, 0.2, 0.],
            [1., 0., 0.], [1.2, 0., 0.], [1., 0.2, 0.],
        ])
        disconnected = validate_mesh(scattered, np.array([[0, 1, 2], [3, 4, 5], [6, 7, 8]]))
        self.assertEqual(disconnected["components"], 3)
        self.assertTrue(WORKER.sanity(disconnected, aligned)["passed"])

        # The same geometry policy rejects a >95% one/two-face debris field only
        # after the mesh has at least 100 faces.
        debris_vertices = []
        debris_faces = []
        pitch = 0.01
        for index in range(98):
            x = index * pitch
            start = len(debris_vertices)
            debris_vertices.extend(([x, 0., 0.], [x + 0.008, 0., 0.], [x, 0.008, 0.]))
            debris_faces.append([start, start + 1, start + 2])
        start = len(debris_vertices)
        debris_vertices.extend(([1.0, 0., 0.], [1.02, 0., 0.], [1.0, 0.02, 0.],
                                [1.02, 0.02, 0.], [1.04, 0.02, 0.]))
        debris_faces.extend(([start, start + 1, start + 2], [start + 2, start + 1, start + 3],
                             [start + 2, start + 3, start + 4]))
        debris = validate_mesh(np.asarray(debris_vertices), np.asarray(debris_faces))
        self.assertEqual(debris["triangles"], 101)
        with self.assertRaisesRegex(ValueError, "Extreme disconnected triangle debris"):
            WORKER.sanity(debris, aligned)

    def test_incompatible_sparse_contracts_fail_with_specific_reason(self):
        cases = (
            ({"units": "millimeters"}, "frame/units incompatible"),
            ({"model_version": 9}, "Unsupported sparse artifact version"),
            ({"status": "disconnected_reconstruction"}, "not an accepted connected metric reconstruction"),
            ({"registered_count": 2}, "At least three registered views"),
            ({"omit_image": _NAMES[0]}, "Registered camera missing source calibration"),
            ({"camera_name_override": "missing.png"}, "Registered camera missing source calibration"),
        )
        for options, expected_message in cases:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as subdir:
                sparse = _write_sparse_fixture(Path(subdir), **options)
                with self.assertRaisesRegex(ValueError, expected_message):
                    WORKER.sparse_inputs(sparse, _config())

    def test_registered_camera_matches_source_calibration_and_image_dimensions(self):
        for field in ("fx", "width", "frameId", "png_dimensions"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as subdir:
                sparse = _write_sparse_fixture(Path(subdir))
                input_path = sparse / "input.json"
                inputs = _json(input_path)
                first = inputs["images"][0]
                if field == "fx":
                    first["fx"] = 501.0
                elif field == "width":
                    first["width"] = 800
                elif field == "frameId":
                    first["frameId"] = "wrong-frame-id"
                else:
                    (sparse / "images" / first["name"]).write_bytes(_png_bytes(800, 480))
                input_path.write_text(json.dumps(inputs), encoding="utf-8")
                with self.assertRaises(ValueError):
                    WORKER.sparse_inputs(sparse, _config())

    def test_pipeline_runs_mesh_cleanup_exports_and_inspection_then_reuses_checkpoints(self):
        run = self.root / "object-run"
        diagnostic, dense_mock = _load_pipeline(self.sparse, run)
        self.assertEqual(diagnostic["status"], "succeeded")
        self.assertEqual(diagnostic["reusedStages"], [])
        self.assertEqual(dense_mock.call_count, 1)
        self.assertTrue((run / "inspection.html").is_file())
        self.assertTrue((run / "mesh-diagnostics.json").is_file())
        self.assertEqual({path.name for path in (run / "exports").iterdir()},
                         {"mesh.ply", "mesh.obj", "mesh.glb"})

        manifest = _json(run / "object-manifest.json")
        self.assertEqual(set(manifest["stages"]), {"dense", "mesh", "export"})
        for stage in manifest["stages"].values():
            self.assertGreaterEqual(stage["runtimeSeconds"], 0.0)
            self.assertTrue(stage["outputHashes"])
            for relative, expected_hash in stage["outputHashes"].items():
                self.assertEqual(WORKER.digest(run / relative), expected_hash)

        # Dense mock vertices are a translated metric plane. The actual surface
        # algorithm, cleanup, inspector, and serializers must preserve observed positions.
        with np.load(run / "dense.npz") as dense:
            np.testing.assert_array_equal(dense["points"], _plane_cloud()[0])
        cloud = o3d.io.read_point_cloud(str(run / "dense-metric.ply"))
        np.testing.assert_allclose(np.asarray(cloud.points), _plane_cloud()[0], atol=1e-7, rtol=0)
        mesh = o3d.io.read_triangle_mesh(str(run / "exports" / "mesh.ply"))
        vertices = np.asarray(mesh.vertices)
        self.assertGreater(len(mesh.triangles), 0)
        self.assertGreater(vertices[:, 2].min(), 3.99)
        self.assertLess(vertices[:, 2].max(), 4.01)
        self.assertGreater(vertices[:, 0].min(), 1.99)
        self.assertLess(vertices[:, 0].max(), 2.31)
        self.assertGreater(vertices[:, 1].min(), -0.16)
        self.assertLess(vertices[:, 1].max(), 0.16)
        self.assertTrue(np.all(np.asarray(mesh.vertex_normals)[:, 2] > 0.99))
        for extension in ("obj", "glb"):
            independent = trimesh.load(run / "exports" / f"mesh.{extension}", force="mesh", process=False)
            self.assertEqual(len(independent.faces), len(mesh.triangles))
            self.assertGreater(np.asarray(independent.vertices)[:, 0].min(), 1.99)

        retry, unused_dense_mock = _load_pipeline(self.sparse, run, dense=mock.Mock(
            side_effect=AssertionError("dense stage should be reused")))
        self.assertEqual(unused_dense_mock.call_count, 0)
        self.assertEqual(retry["status"], "succeeded")
        self.assertEqual(retry["reusedStages"], ["dense", "mesh", "export"])

    def test_corrupted_checkpoint_is_rejected_before_rerunning_dense(self):
        run = self.root / "corrupt-run"
        _load_pipeline(self.sparse, run)
        with (run / "dense-metric.ply").open("ab") as output:
            output.write(b"corruption")
        with mock.patch.object(WORKER, "dense_stage",
                               side_effect=AssertionError("corrupt checkpoint must be rejected first")) as dense:
            with self.assertRaisesRegex(ValueError, "Corrupt checkpoint: dense"):
                WORKER.run_pipeline(self.sparse, run, _config())
        self.assertEqual(dense.call_count, 0)
        diagnostic = _json(run / "diagnostics.json")
        self.assertEqual(diagnostic["status"], "failed")
        self.assertEqual(diagnostic["stage"], "dense")

    def test_failed_dense_stage_keeps_diagnostics_and_retries_from_dense(self):
        run = self.root / "retry-run"
        calls = []

        def fail_after_partial(sparse, output, aligned, model_path, config):
            calls.append("dense")
            (output / "dense.npz").write_bytes(b"partial native stage output")
            (output / "dense-workspace").mkdir(exist_ok=True)
            (output / "dense-workspace" / "partial.log").write_text("kept for diagnosis")
            raise RuntimeError("synthetic CUDA-free dense failure")

        with mock.patch.object(WORKER, "dense_stage", side_effect=fail_after_partial):
            with self.assertRaisesRegex(RuntimeError, "synthetic CUDA-free dense failure"):
                WORKER.run_pipeline(self.sparse, run, _config())
        failed = _json(run / "diagnostics.json")
        self.assertEqual(failed["status"], "failed")
        self.assertEqual(failed["stage"], "dense")
        self.assertEqual(failed["error"]["type"], "RuntimeError")
        self.assertTrue((run / "dense-workspace" / "partial.log").is_file())
        self.assertNotIn("dense", _json(run / "object-manifest.json")["stages"])

        def recover(sparse, output, aligned, model_path, config):
            calls.append("dense")
            return _mock_dense_stage(sparse, output, aligned, model_path, config)

        recovered, _ = _load_pipeline(self.sparse, run, dense=recover)
        self.assertEqual(recovered["status"], "succeeded")
        self.assertEqual(calls, ["dense", "dense"])
        self.assertEqual(recovered["reusedStages"], [])

    @mock.patch("pycolmap.has_cuda", True)
    def test_stale_partial_dense_workspace_is_preserved_and_recomputed(self):
        import pycolmap

        run = self.root / "partial-native-run"
        def fail_with_partial_workspace(sparse, output, aligned, model_path, config):
            workspace = output / "dense-workspace"
            workspace.mkdir()
            (workspace / "stale-fused.ply").write_bytes(b"stale partial native output")
            raise RuntimeError("seed partial dense workspace")

        with mock.patch.object(WORKER, "dense_stage", side_effect=fail_with_partial_workspace):
            with self.assertRaisesRegex(RuntimeError, "seed partial dense workspace"):
                WORKER.run_pipeline(self.sparse, run, _config())
        workspace = run / "dense-workspace"
        self.assertTrue((workspace / "stale-fused.ply").is_file())
        with mock.patch.object(pycolmap, "undistort_images",
                               side_effect=RuntimeError("fresh undistort failed")) as undistort, \
                mock.patch.object(pycolmap, "patch_match_stereo") as stereo:
            with self.assertRaisesRegex(RuntimeError, "fresh undistort failed"):
                WORKER.run_pipeline(self.sparse, run, _config())
        self.assertEqual(undistort.call_count, 1)
        self.assertEqual(stereo.call_count, 0)
        fresh_workspace = Path(undistort.call_args.args[0])
        self.assertEqual(fresh_workspace, run / "dense-workspace")
        self.assertFalse((fresh_workspace / "stale-fused.ply").exists())
        preserved = list(run.glob("failed-dense-workspace-*"))
        self.assertEqual(len(preserved), 1)
        self.assertEqual((preserved[0] / "stale-fused.ply").read_bytes(), b"stale partial native output")
        diagnostic = _json(run / "diagnostics.json")
        self.assertEqual(diagnostic["status"], "failed")
        self.assertEqual(diagnostic["stage"], "dense")
        self.assertEqual(diagnostic["error"]["message"], "fresh undistort failed")
        self.assertFalse((run / "dense.npz").exists())

    def test_missing_cuda_is_an_explicit_failed_dense_stage(self):
        import pycolmap
        run=self.root/"cpu-dense-run"
        with mock.patch.object(pycolmap,"has_cuda",False):
            with self.assertRaisesRegex(RuntimeError,"COLMAP dense stereo requires CUDA"):
                WORKER.run_pipeline(self.sparse,run,_config(),stage="dense")
        diagnostic=_json(run/"diagnostics.json")
        self.assertEqual(diagnostic["status"],"failed")
        self.assertEqual(diagnostic["stage"],"dense")
        self.assertNotIn("dense",_json(run/"object-manifest.json")["stages"])

    def test_export_without_a_completed_mesh_fails_explicitly(self):
        run = self.root / "no-mesh-run"
        with self.assertRaisesRegex(ValueError, "No completed mesh"):
            WORKER.run_pipeline(self.sparse, run, _config(), stage="export")
        diagnostic = _json(run / "diagnostics.json")
        self.assertEqual(diagnostic["status"], "failed")
        self.assertEqual(diagnostic["error"]["type"], "ValueError")
        self.assertFalse((run / "exports").exists())

    def test_validate_rechecks_dense_export_and_required_mesh_hashes(self):
        for name, relative in (("dense", "dense-metric.ply"), ("export", "exports/mesh.obj")):
            with self.subTest(checkpoint=name), tempfile.TemporaryDirectory() as subdir:
                root = Path(subdir)
                sparse = _write_sparse_fixture(root)
                run = root / "validated-run"
                _load_pipeline(sparse, run)
                with (run / relative).open("ab") as output:
                    output.write(b"tampered")
                with mock.patch.object(WORKER, "dense_stage",
                                       side_effect=AssertionError("validate must only inspect checkpoints")) as dense:
                    with self.assertRaisesRegex(ValueError, "Corrupt checkpoint"):
                        WORKER.run_pipeline(sparse, run, _config(), stage="validate")
                self.assertEqual(dense.call_count, 0)
                self.assertEqual(_json(run / "diagnostics.json")["status"], "failed")

        with tempfile.TemporaryDirectory() as subdir:
            root = Path(subdir)
            sparse = _write_sparse_fixture(root)
            run = root / "missing-hashes-run"
            _load_pipeline(sparse, run)
            manifest_path = run / "object-manifest.json"
            manifest = _json(manifest_path)
            manifest["stages"]["mesh"]["outputHashes"] = {}
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(ValueError):
                WORKER.run_pipeline(sparse, run, _config(), stage="validate")
            self.assertEqual(_json(run / "diagnostics.json")["status"], "failed")

    def _seed_dense_manifest(self, run):
        _load_pipeline(self.sparse, run, stage="dense")

    def test_configuration_sparse_input_code_and_runtime_version_mutations_reject_reuse(self):
        # Existing checkpoints are bound to all configured inputs, code and tool versions.
        run = self.root / "mutation-run"
        self._seed_dense_manifest(run)
        with self.assertRaisesRegex(ValueError, "Stale/incompatible object artifacts"):
            WORKER.run_pipeline(self.sparse, run, _config(maxEdgeMeters=0.09), stage="dense")

        # Changing an actual source image changes the sparse input fingerprint.
        image = self.sparse / "images" / _NAMES[0]
        original_image = image.read_bytes()
        image.write_bytes(original_image + b"changed")
        with self.assertRaisesRegex(ValueError, "Stale/incompatible object artifacts"):
            WORKER.run_pipeline(self.sparse, run, _config(), stage="dense")
        image.write_bytes(original_image)

        # Tool version changes are also fingerprint changes even if every file is intact.
        import pycolmap
        with mock.patch.object(pycolmap, "__version__", "test-mutated-version"):
            with self.assertRaisesRegex(ValueError, "Stale/incompatible object artifacts"):
                WORKER.run_pipeline(self.sparse, run, _config(), stage="dense")

        # Simulate a source code change at its actual digest boundary, without
        # modifying files outside this test's ownership.
        original_digest = WORKER.digest

        def changed_code_digest(path):
            value = original_digest(path)
            if Path(path).resolve() == WORKER_PATH.resolve():
                return "0" * 64
            return value

        with mock.patch.object(WORKER, "digest", side_effect=changed_code_digest):
            with self.assertRaisesRegex(ValueError, "Stale/incompatible object artifacts"):
                WORKER.run_pipeline(self.sparse, run, _config(), stage="dense")

    def test_manifest_metadata_tampering_is_rejected_even_if_fingerprint_is_unchanged(self):
        for field in ("config", "versions", "sourceManifestSha256"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as subdir:
                root = Path(subdir)
                sparse = _write_sparse_fixture(root)
                run = root / "tampered-manifest-run"
                _load_pipeline(sparse, run, stage="dense")
                path = run / "object-manifest.json"
                manifest = _json(path)
                fingerprint = manifest["fingerprint"]
                if field == "config":
                    manifest[field]["maxEdgeMeters"] = 19.0
                elif field == "versions":
                    manifest[field]["pycolmap"] = "tampered-version"
                else:
                    manifest[field] = "0" * 64
                self.assertEqual(manifest["fingerprint"], fingerprint)
                path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "Stale/incompatible object artifacts"):
                    WORKER.run_pipeline(sparse, run, _config(), stage="dense")

    def _digest_with_changed_module(self, module_name):
        original_digest = WORKER.digest

        def changed_digest(path):
            result = original_digest(path)
            if Path(path).name == module_name:
                return hashlib.sha256((result + ":synthetic-source-change").encode()).hexdigest()
            return result

        return changed_digest

    def test_geometry_module_change_reuses_dense_archives_mesh_and_export_then_rebuilds(self):
        run = self.root / "geometry-change-run"
        _load_pipeline(self.sparse, run)
        previous = _json(run / "object-manifest.json")
        old_fingerprint = previous["fingerprint"]
        old_dense_hashes = previous["stages"]["dense"]["outputHashes"]
        old_dense_impl = previous["stages"]["dense"]["implementationHash"]
        old_mesh_hash = previous["stages"]["mesh"]["implementationHash"]
        old_export_hash = previous["stages"]["export"]["implementationHash"]
        changed_digest = self._digest_with_changed_module("mesh_geometry.py")

        with mock.patch.object(WORKER, "digest", side_effect=changed_digest), \
                mock.patch.object(WORKER, "dense_stage",
                                  side_effect=AssertionError("geometry edits must reuse completed dense output")) as dense:
            diagnostic = WORKER.run_pipeline(self.sparse, run, _config())

        self.assertEqual(dense.call_count, 0)
        self.assertEqual(diagnostic["status"], "succeeded")
        # dense_stage is not called and its checkpoint hash/implementation remain
        # intact. Worker currently reports a redundant mesh reuse marker after the
        # mesh rebuild's export dependency check, so output/checkpoint evidence is
        # authoritative for the invalidated stages here.
        self.assertIn("dense", diagnostic["reusedStages"])
        current = _json(run / "object-manifest.json")
        self.assertNotEqual(current["fingerprint"], old_fingerprint)
        self.assertEqual(current["implementationHistory"][-1]["fingerprint"], old_fingerprint)
        self.assertEqual(current["implementationHistory"][-1]["code"], previous["code"])
        self.assertEqual(current["stages"]["dense"]["outputHashes"], old_dense_hashes)
        self.assertEqual(current["stages"]["dense"]["implementationHash"], old_dense_impl)
        self.assertNotEqual(current["stages"]["mesh"]["implementationHash"], old_mesh_hash)
        self.assertEqual(current["stages"]["export"]["implementationHash"], old_export_hash)
        for relative, expected_hash in old_dense_hashes.items():
            self.assertEqual(WORKER.digest(run / relative), expected_hash)

        archives = list(run.glob("stale-implementation-*"))
        self.assertEqual(len(archives), 1)
        archive = archives[0]
        for stage in ("mesh", "export"):
            checkpoint = _json(archive / f"{stage}-checkpoint.json")
            for relative, expected_hash in checkpoint["outputHashes"].items():
                self.assertEqual(WORKER.digest(archive / relative), expected_hash)
        self.assertEqual(set(_json(archive / "mesh-checkpoint.json")["outputHashes"]),
                         {"mesh.npz", "mesh-diagnostics.json"})
        self.assertEqual(set(_json(archive / "export-checkpoint.json")["outputHashes"]),
                         {"exports/mesh.ply", "exports/mesh.obj", "exports/mesh.glb"})

    def test_exporter_module_change_invalidates_only_exports(self):
        run = self.root / "exporter-change-run"
        _load_pipeline(self.sparse, run)
        previous = _json(run / "object-manifest.json")
        dense_hashes = previous["stages"]["dense"]["outputHashes"]
        mesh_hashes = previous["stages"]["mesh"]["outputHashes"]
        mesh_impl = previous["stages"]["mesh"]["implementationHash"]
        export_impl = previous["stages"]["export"]["implementationHash"]
        changed_digest = self._digest_with_changed_module("exporters.py")

        with mock.patch.object(WORKER, "digest", side_effect=changed_digest), \
                mock.patch.object(WORKER, "dense_stage",
                                  side_effect=AssertionError("export edits must reuse completed dense output")) as dense:
            diagnostic = WORKER.run_pipeline(self.sparse, run, _config())

        self.assertEqual(dense.call_count, 0)
        self.assertEqual(diagnostic["status"], "succeeded")
        self.assertEqual(diagnostic["reusedStages"], ["dense", "mesh"])
        current = _json(run / "object-manifest.json")
        self.assertEqual(current["stages"]["dense"]["outputHashes"], dense_hashes)
        self.assertEqual(current["stages"]["mesh"]["outputHashes"], mesh_hashes)
        self.assertEqual(current["stages"]["mesh"]["implementationHash"], mesh_impl)
        self.assertNotEqual(current["stages"]["export"]["implementationHash"], export_impl)
        archives = list(run.glob("stale-implementation-*"))
        self.assertEqual(len(archives), 1)
        archive = archives[0]
        self.assertFalse((archive / "mesh-checkpoint.json").exists())
        checkpoint = _json(archive / "export-checkpoint.json")
        self.assertEqual(set(checkpoint["outputHashes"]),
                         {"exports/mesh.ply", "exports/mesh.obj", "exports/mesh.glb"})
        for relative, expected_hash in checkpoint["outputHashes"].items():
            self.assertEqual(WORKER.digest(archive / relative), expected_hash)

    def test_changed_transform_helper_source_rejects_dense_cache(self):
        run = self.root / "transform-change-run"
        _load_pipeline(self.sparse, run, stage="dense")
        original_getsource = WORKER.inspect.getsource

        def changed_source(function):
            source = original_getsource(function)
            if function.__name__ == "transform_cloud":
                return source + "\n# synthetic transform implementation change\n"
            return source

        with mock.patch.object(WORKER.inspect, "getsource", side_effect=changed_source), \
                mock.patch.object(WORKER, "dense_stage",
                                  side_effect=AssertionError("incompatible dense implementation must not run")) as dense:
            with self.assertRaisesRegex(ValueError, "Stale/incompatible dense implementation"):
                WORKER.run_pipeline(self.sparse, run, _config(), stage="dense")
        self.assertEqual(dense.call_count, 0)

    def test_stage_requests_reject_stale_implementation_when_dependencies_are_noop_checks(self):
        # `export` cannot silently pass through a stale geometry stage, and
        # `validate` cannot silently accept stale serialized outputs.
        for module, stage, expected_stage in (("mesh_geometry.py", "export", "mesh"),
                                              ("mesh_geometry.py", "validate", "mesh"),
                                              ("exporters.py", "validate", "export")):
            with self.subTest(module=module, stage=stage), tempfile.TemporaryDirectory() as subdir:
                root = Path(subdir)
                sparse = _write_sparse_fixture(root)
                run = root / "stale-dependency-run"
                _load_pipeline(sparse, run)
                changed_digest = self._digest_with_changed_module(module)
                with mock.patch.object(WORKER, "digest", side_effect=changed_digest), \
                        mock.patch.object(WORKER, "dense_stage",
                                          side_effect=AssertionError("dependency check must not rebuild dense")) as dense:
                    with self.assertRaisesRegex(ValueError, "Stale dependent stage " + expected_stage):
                        WORKER.run_pipeline(sparse, run, _config(), stage=stage)
                self.assertEqual(dense.call_count, 0)


if __name__ == "__main__":
    unittest.main()
