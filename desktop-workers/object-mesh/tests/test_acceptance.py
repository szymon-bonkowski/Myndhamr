"""Regression for independent export parsing with stored corner normals."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np
import open3d as o3d


ROOT = Path(__file__).resolve().parents[3]
OBJECT_MESH = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(OBJECT_MESH))
from exporters import export_mesh
from mesh_geometry import cleanup_mesh, validate_mesh
from worker import sanity

CHECKER_PATH = ROOT / "tools" / "check-object-acceptance.py"
_SPEC = importlib.util.spec_from_file_location("object_acceptance_checker", CHECKER_PATH)
assert _SPEC is not None and _SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(CHECKER)


def _tetrahedron():
    # Non-binary, asymmetric and translated coordinates make accidental axis,
    # scale, and origin changes visible to the independent readers.
    vertices = np.array([
        [2.237, -1.513, 4.071],
        [3.749, -1.487, 4.003],
        [2.553, 0.527, 4.183],
        [2.791, -1.037, 6.113],
    ], dtype=np.float64)
    faces = np.array([[0, 2, 1], [0, 1, 3], [1, 2, 3], [2, 0, 3]], dtype=np.int64)
    return cleanup_mesh(vertices, faces)[:3]


def _pose(center):
    matrix = np.eye(4)
    matrix[:3, 3] = center
    return matrix.flatten(order="F").tolist()


def _angle_weighted_normals(vertices, faces):
    """Independent expected normals from face unit normals weighted by corner angle."""
    accumulated = np.zeros_like(vertices)
    for face in faces:
        triangle = vertices[face]
        cross = np.cross(triangle[1] - triangle[0], triangle[2] - triangle[0])
        face_normal = cross / np.linalg.norm(cross)
        for corner in range(3):
            first = triangle[(corner + 1) % 3] - triangle[corner]
            second = triangle[(corner + 2) % 3] - triangle[corner]
            cosine = np.dot(first, second) / (np.linalg.norm(first) * np.linalg.norm(second))
            accumulated[face[corner]] += np.arccos(np.clip(cosine, -1.0, 1.0)) * face_normal
    return accumulated / np.linalg.norm(accumulated, axis=1)[:, None]


def _fixture(root: Path):
    run = root / "object-run"
    sparse = root / "sparse"
    (run / "exports").mkdir(parents=True)
    (run / "dense-workspace").mkdir()
    sparse.mkdir()
    vertices, faces, normals = _tetrahedron()
    mesh_stats = validate_mesh(vertices, faces, normals)
    aligned = {
        "schemaVersion": 1, "units": "meters", "frame": "capture-world",
        "alignment": {"scale": 1.0, "rotationWorldSfmRowMajor": np.eye(3).ravel().tolist(),
                      "translationWorldSfmMeters": [0.0, 0.0, 0.0]},
        "cameras": [
            {"worldFromOpticalColumnMajor": _pose((0.0, 0.0, 0.0))},
            {"worldFromOpticalColumnMajor": _pose((0.5, 0.0, 0.0))},
            {"worldFromOpticalColumnMajor": _pose((0.0, 0.5, 0.0))},
        ],
    }
    (sparse / "aligned-model.json").write_text(json.dumps(aligned), encoding="utf-8")

    dense_points = vertices.copy()
    dense_normals = normals.copy()
    np.savez(run / "dense.npz", points=dense_points, normals=dense_normals)
    cloud = o3d.geometry.PointCloud()
    cloud.points = o3d.utility.Vector3dVector(dense_points)
    cloud.normals = o3d.utility.Vector3dVector(dense_normals)
    if not o3d.io.write_point_cloud(str(run / "dense-workspace" / "fused.ply"), cloud):
        raise AssertionError("Open3D failed to write the acceptance fixture cloud")
    np.savez(run / "mesh.npz", vertices=vertices, faces=faces, normals=normals)
    mesh_stats["sanity"] = sanity(mesh_stats, aligned)
    (run / "mesh-diagnostics.json").write_text(json.dumps({"mesh": mesh_stats}), encoding="utf-8")
    result = export_mesh(vertices, faces, normals, run / "exports")
    result = {fmt: {**entry, "path": str(Path(entry["path"]).relative_to(run))}
              for fmt, entry in result.items()}
    stages = {
        "dense": {"runtimeSeconds": 0.0, "statistics": {"densePoints": len(dense_points)},
                  "outputHashes": {}},
        "mesh": {"runtimeSeconds": 0.0, "statistics": {"mesh": mesh_stats}, "outputHashes": {}},
        "export": {"runtimeSeconds": 0.0, "statistics": result, "outputHashes": {}},
    }
    manifest = {"schemaVersion": 1, "sparseInputPath": str(sparse),
                "config": {"formats": ["ply", "obj", "glb"]}, "stages": stages}
    diagnostic = {"status": "succeeded", "peakRssBytes": 1, "artifactBytes": 1}
    (run / "object-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (run / "diagnostics.json").write_text(json.dumps(diagnostic), encoding="utf-8")
    return run, vertices, faces, normals


class AcceptanceParserTests(unittest.TestCase):
    def test_asymmetric_tetrahedron_preserves_area_weighted_normals_and_support(self):
        with tempfile.TemporaryDirectory() as subdir:
            run, vertices, faces, normals = _fixture(Path(subdir))
            self.assertEqual(len(vertices), 4)
            self.assertEqual(len(faces), 4)
            self.assertTrue(np.isfinite(normals).all())

            # Trimesh computes angle-weighted vertex normals for some formats;
            # those differ measurably from cleanup's stored area-weighted data.
            angle_normals = _angle_weighted_normals(vertices, faces)
            self.assertGreater(float(np.max(np.abs(angle_normals - normals))), 1e-3)

            accepted = CHECKER.check(run)
            self.assertEqual(accepted["acceptance"], "passed")
            self.assertEqual(set(accepted["exports"]), {"ply", "obj", "glb"})
            self.assertTrue(all(result["passed"] for result in accepted["exports"].values()))
            self.assertEqual(accepted["maximumVertexSupportDistanceMeters"], 0.0)

    def test_wrong_stored_ply_normal_is_rejected_by_native_triangle_corner_oracle(self):
        with tempfile.TemporaryDirectory() as subdir:
            run, vertices, faces, normals = _fixture(Path(subdir))
            wrong_normals = normals.copy()
            wrong_normals[0] = [0.0, 0.0, 1.0]
            self.assertFalse(np.allclose(wrong_normals[0], normals[0]))
            export_mesh(vertices, faces, wrong_normals, run / "exports", formats=("ply",))
            with self.assertRaises(AssertionError):
                CHECKER.check(run)


if __name__ == "__main__":
    unittest.main()
