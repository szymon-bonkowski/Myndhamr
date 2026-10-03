from __future__ import annotations

import importlib.util
import json
import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np
import open3d as o3d
import trimesh


EXPORTER = Path(__file__).resolve().parents[1] / "exporters.py"
_SPEC = importlib.util.spec_from_file_location("object_mesh_exporters", EXPORTER)
assert _SPEC is not None and _SPEC.loader is not None
_MODULE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)
export_mesh = _MODULE.export_mesh


def _asymmetric_tetrahedron():
    # A translated, asymmetric tetrahedron catches recentering, axis flips,
    # reflections, and accidental unit scaling that a symmetric origin fixture misses.
    vertices = np.array([
        [2.25, -1.5, 4.0],
        [3.75, -1.5, 4.0],
        [2.5, 0.5, 4.0],
        [2.75, -1.0, 6.0],
    ], dtype=np.float64)
    faces = np.array([[0, 2, 1], [0, 1, 3], [1, 2, 3], [2, 0, 3]], dtype=np.int32)
    normals = np.zeros_like(vertices)
    for face in faces:
        a, b, c = vertices[face]
        face_normal = np.cross(b - a, c - a)
        for index in face:
            normals[index] += face_normal
    normals /= np.linalg.norm(normals, axis=1)[:, None]
    return vertices, faces, normals


def _signed_volume(vertices, faces):
    triangles = vertices[faces]
    return float(np.einsum("ij,ij->i", triangles[:, 0],
                           np.cross(triangles[:, 1], triangles[:, 2])).sum() / 6.0)


def _expected_vertex_indices(actual_vertices, expected_vertices):
    """Map a reader's vertex order back to the source order without tolerating transforms."""
    mapping = []
    for vertex in actual_vertices:
        matches = np.flatnonzero(np.all(expected_vertices == vertex, axis=1))
        if len(matches) != 1:
            raise AssertionError(f"reader changed or duplicated a vertex: {vertex}")
        mapping.append(int(matches[0]))
    if sorted(mapping) != list(range(len(expected_vertices))):
        raise AssertionError("reader omitted or duplicated vertices")
    return np.asarray(mapping, dtype=np.int64)


def _oriented_face_cycles(faces, reader_to_expected):
    """Canonicalize cyclic starts while retaining the direction of each face."""
    canonical = []
    for face in np.asarray(faces):
        expected = tuple(int(reader_to_expected[index]) for index in face)
        rotations = (expected, expected[1:] + expected[:1], expected[2:] + expected[:2])
        canonical.append(min(rotations))
    return sorted(canonical)


def _read_glb(path: Path):
    data = path.read_bytes()
    magic, version, total_length = struct.unpack_from("<4sII", data, 0)
    assert magic == b"glTF"
    assert version == 2
    assert total_length == len(data)
    json_length, json_kind = struct.unpack_from("<I4s", data, 12)
    assert json_kind == b"JSON"
    gltf = json.loads(data[20:20 + json_length].decode("utf-8"))
    bin_header = 20 + json_length
    binary_length, binary_kind = struct.unpack_from("<I4s", data, bin_header)
    assert binary_kind == b"BIN\0"
    binary = data[bin_header + 8:bin_header + 8 + binary_length]
    assert bin_header + 8 + binary_length == len(data)
    return gltf, binary


class ExporterValidationTests(unittest.TestCase):
    def setUp(self):
        self.vertices, self.faces, self.normals = _asymmetric_tetrahedron()
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_ply_obj_and_glb_preserve_metric_geometry_winding_and_normals(self):
        result = export_mesh(self.vertices, self.faces, self.normals, self.directory)
        self.assertEqual(set(result), {"ply", "obj", "glb"})
        for name, metadata in result.items():
            self.assertEqual(metadata["path"], self.directory / f"mesh.{name}")
            self.assertEqual(metadata["sizeBytes"], metadata["path"].stat().st_size)

        expected_volume = _signed_volume(self.vertices, self.faces)
        self.assertGreater(expected_volume, 0.0)

        # Open3D and trimesh independently parse the text exporters.  Exact
        # vertex comparison detects all translations, 1000x scale errors,
        # reflections, or axis conversions; normals and winding are checked too.
        for name in ("ply", "obj"):
            path = self.directory / f"mesh.{name}"
            mesh = o3d.io.read_triangle_mesh(str(path), enable_post_processing=False)
            self.assertEqual(len(mesh.vertices), len(self.vertices))
            self.assertEqual(len(mesh.triangles), len(self.faces))
            o3d_vertices = np.asarray(mesh.vertices)
            o3d_faces = np.asarray(mesh.triangles)
            o3d_order = _expected_vertex_indices(o3d_vertices, self.vertices)
            np.testing.assert_allclose(o3d_vertices, self.vertices[o3d_order], atol=1e-12, rtol=0)
            np.testing.assert_allclose(np.asarray(mesh.vertex_normals), self.normals[o3d_order], atol=1e-7, rtol=0)
            self.assertEqual(_oriented_face_cycles(o3d_faces, o3d_order),
                             _oriented_face_cycles(self.faces, np.arange(len(self.vertices))))
            self.assertAlmostEqual(_signed_volume(o3d_vertices, o3d_faces),
                                   expected_volume, places=12)
            np.testing.assert_allclose(o3d_vertices.min(axis=0), self.vertices.min(axis=0))
            np.testing.assert_allclose(o3d_vertices.max(axis=0), self.vertices.max(axis=0))

            tri = trimesh.load(path, force="mesh", process=False)
            self.assertEqual(len(tri.vertices), len(self.vertices))
            self.assertEqual(len(tri.faces), len(self.faces))
            tri_vertices = np.asarray(tri.vertices)
            tri_faces = np.asarray(tri.faces)
            tri_order = _expected_vertex_indices(tri_vertices, self.vertices)
            np.testing.assert_allclose(tri_vertices, self.vertices[tri_order], atol=1e-12, rtol=0)
            self.assertEqual(_oriented_face_cycles(tri_faces, tri_order),
                             _oriented_face_cycles(self.faces, np.arange(len(self.vertices))))
            self.assertAlmostEqual(_signed_volume(tri_vertices, tri_faces),
                                   expected_volume, places=12)

        # The GLB is structurally inspected, then independently loaded by trimesh.
        gltf, binary = _read_glb(self.directory / "mesh.glb")
        self.assertEqual(gltf["asset"]["extras"]["units"], "meters")
        self.assertEqual(gltf["asset"]["extras"]["frame"], "capture-world")
        self.assertEqual(gltf["asset"]["extras"]["handedness"], "right")
        self.assertEqual(gltf["asset"]["extras"]["upAxis"], "+Y")
        self.assertEqual(gltf["nodes"][0]["matrix"], [1, 0, 0, 0, 0, 1, 0, 0,
                                                        0, 0, 1, 0, 0, 0, 0, 1])
        accessors = gltf["accessors"]
        views = gltf["bufferViews"]
        self.assertEqual([a["componentType"] for a in accessors], [5126, 5126, 5125])
        self.assertEqual([a["count"] for a in accessors], [4, 4, 12])
        self.assertEqual([a["type"] for a in accessors], ["VEC3", "VEC3", "SCALAR"])
        self.assertEqual([v["target"] for v in views], [34962, 34962, 34963])
        self.assertTrue(all(v["byteOffset"] % 4 == 0 for v in views))
        self.assertEqual([v["byteOffset"] for v in views], [0, 48, 96])
        self.assertEqual([v["byteLength"] for v in views], [48, 48, 48])
        np.testing.assert_array_equal(accessors[0]["min"], self.vertices.min(axis=0).astype(np.float32))
        np.testing.assert_array_equal(accessors[0]["max"], self.vertices.max(axis=0).astype(np.float32))
        glb_vertices = np.frombuffer(binary, dtype="<f4", count=12, offset=views[0]["byteOffset"]).reshape(-1, 3)
        glb_normals = np.frombuffer(binary, dtype="<f4", count=12, offset=views[1]["byteOffset"]).reshape(-1, 3)
        glb_indices = np.frombuffer(binary, dtype="<u4", count=12, offset=views[2]["byteOffset"]).reshape(-1, 3)
        np.testing.assert_allclose(glb_vertices, self.vertices, atol=1e-6, rtol=0)
        np.testing.assert_allclose(glb_normals, self.normals, atol=1e-6, rtol=0)
        np.testing.assert_array_equal(glb_indices, self.faces)
        self.assertAlmostEqual(_signed_volume(glb_vertices, glb_indices), expected_volume, places=5)

        tri_glb = trimesh.load(self.directory / "mesh.glb", force="mesh", process=False)
        self.assertEqual(len(tri_glb.vertices), 4)
        self.assertEqual(len(tri_glb.faces), 4)
        tri_glb_vertices = np.asarray(tri_glb.vertices)
        tri_glb_faces = np.asarray(tri_glb.faces)
        tri_glb_order = _expected_vertex_indices(tri_glb_vertices, self.vertices.astype(np.float32))
        np.testing.assert_allclose(tri_glb_vertices, self.vertices[tri_glb_order], atol=1e-6, rtol=0)
        self.assertEqual(_oriented_face_cycles(tri_glb_faces, tri_glb_order),
                         _oriented_face_cycles(self.faces, np.arange(len(self.vertices))))
        self.assertAlmostEqual(_signed_volume(tri_glb_vertices, tri_glb_faces),
                               expected_volume, places=5)

        glb_o3d = o3d.io.read_triangle_mesh(str(self.directory / "mesh.glb"), enable_post_processing=False)
        glb_o3d_vertices = np.asarray(glb_o3d.vertices)
        glb_o3d_order = _expected_vertex_indices(glb_o3d_vertices, self.vertices.astype(np.float32))
        np.testing.assert_allclose(np.asarray(glb_o3d.vertex_normals), self.normals[glb_o3d_order],
                                   atol=1e-6, rtol=0)

    def test_obj_uses_vertex_aligned_normal_indices_and_ccw_faces(self):
        export_mesh(self.vertices, self.faces, self.normals, self.directory, formats=("obj",))
        lines = (self.directory / "mesh.obj").read_text(encoding="ascii").splitlines()
        self.assertEqual(sum(line.startswith("v ") for line in lines), 4)
        self.assertEqual(sum(line.startswith("vn ") for line in lines), 4)
        faces = [line for line in lines if line.startswith("f ")]
        self.assertEqual(len(faces), 4)
        parsed = np.array([[int(token.split("//")[0]) - 1 for token in line.split()[1:]]
                           for line in faces])
        np.testing.assert_array_equal(parsed, self.faces)
        for line in faces:
            self.assertTrue(all(token.split("//")[0] == token.split("//")[1]
                                for token in line.split()[1:]))

    def test_invalid_inputs_and_formats_create_no_valid_outputs(self):
        bad_cases = [
            (self.vertices, self.faces.astype(float), self.normals, ("ply",)),
            (self.vertices, np.array([[0, 1, 9]], dtype=np.int64), self.normals, ("ply",)),
            (self.vertices, self.faces, np.zeros_like(self.normals), ("ply",)),
            (self.vertices, self.faces, self.normals * 2.0, ("ply",)),
            (self.vertices, self.faces, np.full_like(self.normals, np.nan), ("ply",)),
            (self.vertices.copy(), self.faces, self.normals, ("ply",)),
        ]
        bad_cases[-1][0][0, 0] = np.nan
        for vertices, faces, normals, formats in bad_cases:
            with self.subTest(vertices=vertices, faces=faces, normals=normals):
                with self.assertRaises(ValueError):
                    export_mesh(vertices, faces, normals, self.directory, formats=formats)
                self.assertEqual(list(self.directory.iterdir()), [])
        for formats in (("unknown",), (), ("ply", "ply"), ([],)):
            with self.subTest(formats=formats):
                with self.assertRaises(ValueError):
                    export_mesh(self.vertices, self.faces, self.normals, self.directory, formats=formats)
                self.assertEqual(list(self.directory.iterdir()), [])

    def test_glb_float32_overflow_fails_before_any_output(self):
        vertices = self.vertices.copy()
        vertices[0, 0] = 1e100
        with self.assertRaisesRegex(ValueError, "float32 range"):
            export_mesh(vertices, self.faces, self.normals, self.directory, formats=("glb",))
        self.assertEqual(list(self.directory.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
