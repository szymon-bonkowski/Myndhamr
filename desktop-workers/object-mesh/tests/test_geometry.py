"""Analytical geometry/metric-frame fixtures, including native ball pivoting."""
import json
from pathlib import Path
import sys
import unittest

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from mesh_geometry import cleanup_mesh, split_ambiguous_topology, surface_mesh, transform_cloud, validate_mesh


def plane():
    return (np.array([[0., 0., 0.], [2., 0., 0.], [2., 3., 0.], [0., 3., 0.]]),
            np.array([[0, 1, 2], [0, 2, 3]], dtype=np.int64))


def tetrahedron():
    # Positive orientation, outward-facing triangles, analytic volume 1/6 m^3.
    vertices = np.array([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.], [0., 0., 1.]])
    faces = np.array([[0, 2, 1], [0, 1, 3], [0, 3, 2], [1, 2, 3]])
    return vertices, faces


def box():
    vertices = np.array([[0., 0., 0.], [2., 0., 0.], [2., 3., 0.], [0., 3., 0.],
                         [0., 0., 4.], [2., 0., 4.], [2., 3., 4.], [0., 3., 4.]])
    faces = np.array([[0, 2, 1], [0, 3, 2], [4, 5, 6], [4, 6, 7],
                      [0, 1, 5], [0, 5, 4], [1, 2, 6], [1, 6, 5],
                      [2, 3, 7], [2, 7, 6], [3, 0, 4], [3, 4, 7]])
    return vertices, faces


def signed_volume(vertices, faces):
    # Centering makes this translation-invariant numerically as well as analytically.
    p = vertices - vertices.mean(axis=0)
    return float(np.einsum("ij,ij->i", p[faces[:, 0]],
                          np.cross(p[faces[:, 1]], p[faces[:, 2]])).sum() / 6)


def alignment(scale=1., rotation=None, translation=None):
    return {"scale": scale,
            "rotationWorldSfmRowMajor": (np.eye(3) if rotation is None else rotation).ravel().tolist(),
            "translationWorldSfmMeters": [0., 0., 0.] if translation is None else translation}


class GeometryTests(unittest.TestCase):
    def test_metric_plane_known_area_bounds_boundary_normals(self):
        vertices, faces = plane()
        v, f, normals, audit = cleanup_mesh(vertices, faces)
        stats = audit["after"]
        self.assertEqual(stats["surfaceAreaSquareMeters"], 6.)
        self.assertEqual(stats["dimensionsMeters"], [2., 3., 0.])
        self.assertEqual(stats["boundsMeters"], {"min": [0., 0., 0.], "max": [2., 3., 0.]})
        self.assertEqual((stats["vertices"], stats["triangles"], stats["components"]), (4, 2, 1))
        self.assertEqual(stats["boundaryEdges"], 4)
        self.assertEqual(stats["windingConflictEdges"], 0)
        np.testing.assert_array_equal(v, vertices)
        np.testing.assert_array_equal(f, faces)
        np.testing.assert_array_equal(normals, np.tile([0., 0., 1.], (4, 1)))
        json.dumps(audit, allow_nan=False)

    def test_box_known_area_volume_and_closed_topology(self):
        vertices, faces = box()
        v, f, normals, audit = cleanup_mesh(vertices, faces)
        self.assertAlmostEqual(audit["after"]["surfaceAreaSquareMeters"], 52.)
        self.assertAlmostEqual(signed_volume(v, f), 24.)
        self.assertEqual(audit["after"]["dimensionsMeters"], [2., 3., 4.])
        for key in ("boundaryEdges", "nonManifoldEdges", "windingConflictEdges", "zeroNormalVertices"):
            self.assertEqual(audit["after"][key], 0)
        # Normals at every corner point into their proper spatial octant.
        self.assertTrue(np.all((v - [1, 1.5, 2]) * normals > 0))
        np.testing.assert_allclose(np.linalg.norm(normals, axis=1), 1., atol=1e-15)

    def test_tetrahedron_area_and_signed_volume(self):
        vertices, faces = tetrahedron()
        v, f, _, audit = cleanup_mesh(vertices, faces)
        self.assertAlmostEqual(audit["after"]["surfaceAreaSquareMeters"], (3 + np.sqrt(3)) / 2)
        self.assertAlmostEqual(signed_volume(v, f), 1 / 6)

    def test_clockwise_plane_remains_clockwise(self):
        vertices, faces = plane()
        _, result, normals, _ = cleanup_mesh(vertices, faces[:, ::-1])
        np.testing.assert_array_equal(result, faces[:, ::-1])
        np.testing.assert_array_equal(normals, np.tile([0., 0., -1.], (4, 1)))

    def test_area_weighted_normals_known_answer(self):
        vertices = np.array([[0., 0., 0.], [2., 0., 0.], [0., 1., 0.], [0., 0., 3.]])
        faces = np.array([[0, 1, 2], [0, 3, 1]])
        _, _, normals, _ = cleanup_mesh(vertices, faces)
        np.testing.assert_allclose(normals[0], np.array([0, 6, 2]) / np.sqrt(40), atol=1e-15)
        np.testing.assert_array_equal(normals[2], [0, 0, 1])
        np.testing.assert_array_equal(normals[3], [0, 1, 0])

    def test_disconnected_components_retained_and_explicitly_removed(self):
        vertices, faces = plane()
        vertices = np.vstack([vertices, [[10, 0, 0], [11, 0, 0], [10, 1, 0]]])
        faces = np.vstack([faces, [4, 5, 6]])
        v, f, _, audit = cleanup_mesh(vertices, faces)
        self.assertEqual(audit["after"]["components"], 2)
        self.assertEqual(len(f), 3)
        np.testing.assert_array_equal(v, vertices)
        v, f, _, audit = cleanup_mesh(vertices, faces, min_component_faces=2)
        self.assertEqual(audit["after"]["components"], 1)
        self.assertEqual(len(f), 2)
        np.testing.assert_array_equal(v, vertices[:4])
        self.assertEqual(audit["removals"]["componentTriangles"], 1)
        self.assertEqual(audit["removals"]["components"][0]["boundsMeters"]["min"], [10., 0., 0.])
        self.assertEqual(audit["removals"]["unreferencedVertices"], 3)
        with self.assertRaisesRegex(ValueError, "empty"):
            cleanup_mesh(vertices, faces, min_component_faces=3)

    def test_vertex_touch_does_not_merge_edge_components(self):
        vertices = np.array([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.],
                             [-1., 0., 0.], [0., -1., 0.]])
        faces = np.array([[0, 1, 2], [0, 3, 4]])
        self.assertEqual(validate_mesh(vertices, faces)["components"], 2)

    def test_exact_cleanup_preserves_first_occurrence_and_audits(self):
        vertices, _ = plane()
        vertices = np.vstack([vertices, vertices[0], [[99., 99., 99.]]])
        faces = np.array([[4, 1, 2], [0, 2, 3], [2, 1, 0], [0, 0, 3]])
        v, f, _, audit = cleanup_mesh(vertices, faces)
        np.testing.assert_array_equal(v, vertices[:4])
        np.testing.assert_array_equal(f, [[0, 1, 2], [0, 2, 3]])
        self.assertEqual(audit["removals"], {"duplicateVertices": 1, "zeroAreaTriangles": 1,
            "duplicateTriangles": 1, "unreferencedVertices": 1, "componentTriangles": 0,
            "components": []})

    def test_nearby_vertices_are_not_merged(self):
        vertices = np.array([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.],
                             [1e-12, 0., 0.], [1., 0., 1.], [0., 1., 1.]])
        _, _, _, audit = cleanup_mesh(vertices, np.array([[0, 1, 2], [3, 4, 5]]))
        self.assertEqual(audit["after"]["vertices"], 6)
        self.assertEqual(audit["removals"]["duplicateVertices"], 0)

    def test_collinear_zero_area_removed_and_near_zero_retained(self):
        vertices = np.array([[0., 0., 0.], [1., 0., 0.], [2., 0., 0.], [0., 1e-18, 0.]])
        _, f, normals, audit = cleanup_mesh(vertices, np.array([[0, 1, 2], [0, 1, 3]]))
        self.assertEqual(len(f), 1)
        self.assertEqual(audit["removals"]["zeroAreaTriangles"], 1)
        self.assertEqual(audit["after"]["nearDegenerateTriangles"], 1)
        np.testing.assert_array_equal(normals, np.tile([0., 0., 1.], (3, 1)))

    def test_diagnostics_winding_nonmanifold_and_duplicate_topology(self):
        vertices, faces = plane()
        stats = validate_mesh(vertices, np.vstack([faces[0], faces[1, ::-1]]))
        self.assertEqual(stats["windingConflictEdges"], 1)
        extra = np.vstack([vertices, [1., 1., 1.]])
        stats = validate_mesh(extra, np.array([[0, 1, 2], [0, 1, 3], [1, 0, 4]]))
        self.assertEqual(stats["nonManifoldEdges"], 1)
        stats = validate_mesh(vertices, np.vstack([faces, faces[0, ::-1]]))
        self.assertEqual(stats["duplicateTriangles"], 1)

    def test_cancelling_normal_fails_without_winding_repair(self):
        vertices, faces = plane()
        with self.assertRaisesRegex(ValueError, "undefined.*normals"):
            cleanup_mesh(vertices, np.vstack([faces[0], faces[1, ::-1]]))

    def test_empty_validate_and_cleanup_failure(self):
        stats = validate_mesh(np.empty((0, 3)), np.empty((0, 3), dtype=int))
        self.assertEqual(stats["triangles"], 0)
        self.assertIsNone(stats["boundsMeters"])
        with self.assertRaisesRegex(ValueError, "empty"):
            cleanup_mesh(np.empty((0, 3)), np.empty((0, 3), dtype=int))
        with self.assertRaisesRegex(ValueError, "empty"):
            cleanup_mesh(np.array([[0., 0., 0.], [1., 0., 0.], [2., 0., 0.]]), np.array([[0, 1, 2]]))

    def test_malformed_geometry_and_normals_fail(self):
        vertices, faces = plane()
        for indices in (faces.astype(float), np.array([[0, 1, -1]]), np.array([[0, 1, 4]]),
                        np.array([[0, 1]]), np.array([[0, 1, 2]], dtype=bool)):
            with self.subTest(indices=indices), self.assertRaises(ValueError):
                validate_mesh(vertices, indices)
        for scalar in (float("nan"), float("inf"), -float("inf")):
            invalid = vertices.copy(); invalid[0, 0] = scalar
            with self.assertRaises(ValueError):
                cleanup_mesh(invalid, faces)
        for invalid in (np.zeros((4, 2)), np.zeros((3, 3)), np.full((4, 3), np.nan)):
            with self.assertRaises(ValueError):
                validate_mesh(vertices, faces, invalid)
        stats = validate_mesh(vertices, faces, np.zeros((4, 3)))
        self.assertEqual(stats["zeroNormalVertices"], 4)
        for threshold in (-1, True, 2.0):
            with self.assertRaises(ValueError):
                cleanup_mesh(vertices, faces, threshold)

    def test_metric_scaling_area_and_translation_do_not_change_cleanup(self):
        vertices, faces = plane()
        for scale in (1e-9, 1., 1e9):
            _, f, normals, audit = cleanup_mesh(vertices * scale, faces)
            self.assertEqual(len(f), 2)
            self.assertAlmostEqual(audit["after"]["surfaceAreaSquareMeters"] / scale**2, 6.)
            np.testing.assert_array_equal(normals, np.tile([0., 0., 1.], (4, 1)))
        translated = vertices + [10., -20., 30.]
        result, _, _, audit = cleanup_mesh(translated, faces)
        np.testing.assert_array_equal(result, translated)
        self.assertAlmostEqual(audit["after"]["surfaceAreaSquareMeters"], 6.)


class TransformTests(unittest.TestCase):
    def setUp(self):
        self.points = np.array([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.], [0., 0., 1.]])
        self.normals = np.tile([1., 0., 0.], (4, 1))

    def test_identity_translation_and_scale(self):
        for transform, expected in ((alignment(), self.points),
                                    (alignment(translation=[3., -2., 5.]), self.points + [3., -2., 5.]),
                                    (alignment(scale=2.), 2 * self.points)):
            points, normals = transform_cloud(self.points, self.normals * 8, transform)
            np.testing.assert_array_equal(points, expected)
            np.testing.assert_array_equal(normals, self.normals)

    def test_rotation_axes_signed_volume_and_round_trip(self):
        rotation = np.array([[0., -1., 0.], [1., 0., 0.], [0., 0., 1.]])
        translation = np.array([10., 20., -30.])
        p, n = transform_cloud(self.points, self.normals, alignment(2, rotation, translation.tolist()))
        np.testing.assert_array_equal(p - translation, [[0, 0, 0], [0, 2, 0], [-2, 0, 0], [0, 0, 2]])
        np.testing.assert_array_equal(n, np.tile([0., 1., 0.], (4, 1)))
        _, faces = tetrahedron()
        self.assertAlmostEqual(signed_volume(p, faces), 8 / 6)
        reverse = alignment(.5, rotation.T, (-.5 * rotation.T @ translation).tolist())
        recovered, normals = transform_cloud(p, n, reverse)
        np.testing.assert_array_equal(recovered, self.points)
        np.testing.assert_array_equal(normals, self.normals)

    def test_improper_malformed_nonfinite_transform_rejected(self):
        cases = [alignment(-1), alignment(0), alignment(np.nan), alignment(np.inf),
                 alignment(rotation=np.diag([-1, 1, 1])), alignment(rotation=np.eye(3) * 2),
                 alignment(rotation=np.array([[1., .01, 0.], [0., 1., 0.], [0., 0., 1.]])),
                 alignment(translation=[np.inf, 0., 0.]), {},
                 {**alignment(), "rotationWorldSfmRowMajor": np.eye(3).tolist()}]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                transform_cloud(self.points, self.normals, case)
        for normals in (self.normals[:3], np.zeros((4, 3)), np.full((4, 3), np.nan)):
            with self.assertRaises(ValueError):
                transform_cloud(self.points, normals, alignment())


class TopologySeamTests(unittest.TestCase):
    def assert_preserved_manifold(self, vertices, faces, result):
        v, f, normals, audit = result
        np.testing.assert_array_equal(v[f], vertices[faces])
        self.assertEqual(len(f), len(faces))
        self.assertAlmostEqual(audit["before"]["surfaceAreaSquareMeters"],
                               audit["after"]["surfaceAreaSquareMeters"])
        for field in ("nonManifoldEdges", "nonManifoldVertices", "windingConflictEdges", "zeroNormalVertices"):
            self.assertEqual(audit["after"][field], 0)
        self.assertEqual(audit["removedTriangles"], 0)
        self.assertEqual(audit["flippedTriangles"], 0)
        self.assertEqual(audit["movedVertices"], 0)
        self.assertTrue(audit["triangleCornersExactlyPreserved"])
        np.testing.assert_allclose(np.linalg.norm(normals, axis=1), 1., atol=1e-15)
        json.dumps(audit, allow_nan=False)

    def test_consistent_mesh_is_unchanged(self):
        vertices, faces = tetrahedron()
        result = split_ambiguous_topology(vertices, faces)
        self.assert_preserved_manifold(vertices, faces, result)
        np.testing.assert_array_equal(result[0], vertices)
        np.testing.assert_array_equal(result[1], faces)
        self.assertEqual(result[3]["createdSeamVertices"], 0)

    def test_opposite_plane_faces_keep_winding_and_open_audited_seam(self):
        vertices, faces = plane()
        faces[1] = faces[1, ::-1]
        result = split_ambiguous_topology(vertices, faces)
        self.assert_preserved_manifold(vertices, faces, result)
        v, f, normals, audit = result
        self.assertEqual(audit["before"]["windingConflictEdges"], 1)
        self.assertEqual(audit["isolatedFaces"], 2)
        self.assertEqual(audit["isolatedFaceIndices"], [0, 1])
        self.assertEqual(audit["after"]["components"], 2)
        self.assertEqual(audit["after"]["boundaryEdges"], 6)
        self.assertEqual(audit["after"]["duplicateVertices"], 2)
        np.testing.assert_array_equal(normals[f[0]], np.tile([0., 0., 1.], (3, 1)))
        np.testing.assert_array_equal(normals[f[1]], np.tile([0., 0., -1.], (3, 1)))

    def test_disconnected_vertex_fans_split_without_dropping_faces(self):
        vertices = np.array([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.],
                             [0., -1., 0.], [0., 0., 1.]])
        faces = np.array([[0, 1, 2], [0, 3, 4]])
        result = split_ambiguous_topology(vertices, faces)
        self.assert_preserved_manifold(vertices, faces, result)
        _, f, normals, audit = result
        self.assertEqual(audit["before"]["nonManifoldVertices"], 1)
        self.assertEqual(audit["disconnectedFanSplits"], 1)
        self.assertEqual(audit["isolatedFaces"], 0)
        self.assertEqual(audit["after"]["vertices"], 6)
        np.testing.assert_array_equal(normals[f[0]], np.tile([0., 0., 1.], (3, 1)))
        np.testing.assert_array_equal(normals[f[1]], np.tile([-1., 0., 0.], (3, 1)))

    def test_nonorientable_mobius_strip_preserves_entire_observed_geometry(self):
        import open3d as o3d
        vertices = []
        count = 9
        for index in range(count):
            angle = 2 * np.pi * index / count
            for transverse in (-.2, .2):
                radius = 1 + transverse * np.cos(angle / 2)
                vertices.append([radius * np.cos(angle), radius * np.sin(angle),
                                 transverse * np.sin(angle / 2)])
        faces = []
        for index in range(count):
            a, b = 2 * index, 2 * index + 1
            c, d = (2 * (index + 1), 2 * (index + 1) + 1) if index < count - 1 else (1, 0)
            faces.extend([[a, b, c], [b, d, c]])
        vertices, faces = np.asarray(vertices), np.asarray(faces)
        native = o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(vertices),
                                          o3d.utility.Vector3iVector(faces))
        self.assertFalse(native.is_orientable())
        result = split_ambiguous_topology(vertices, faces)
        self.assert_preserved_manifold(vertices, faces, result)
        self.assertGreater(result[3]["isolatedFaces"], 0)
        self.assertGreater(result[3]["after"]["boundaryEdges"], result[3]["before"]["boundaryEdges"])

    def test_reject_nonmanifold_edge_without_deleting_observations(self):
        vertices = np.array([[0., 0., 0.], [1., 0., 0.], [0., 1., 0.],
                             [0., 0., 1.], [0., -1., 0.]])
        faces = np.array([[0, 1, 2], [1, 0, 3], [0, 1, 4]])
        with self.assertRaisesRegex(ValueError, "cannot interpret nonmanifold edges"):
            split_ambiguous_topology(vertices, faces)


class SurfaceTests(unittest.TestCase):
    def setUp(self):
        x, y = np.meshgrid(np.linspace(0., .04, 5), np.linspace(0., .04, 5))
        self.points = np.column_stack([x.ravel(), y.ravel(), np.zeros(x.size)])
        self.normals = np.tile([0., 0., 1.], (len(self.points), 1))

    def test_native_observed_plane_no_new_positions_or_axes(self):
        v, faces, normals, stats = surface_mesh(self.points, self.normals, [.009, .015], .02)
        self.assertGreater(len(faces), 0)
        self.assertAlmostEqual(stats["after"]["surfaceAreaSquareMeters"], .04**2)
        self.assertEqual(stats["after"]["dimensionsMeters"], [.04, .04, 0.])
        self.assertEqual(stats["rejectedLongEdgeTriangles"], 0)
        for vertex in v:
            self.assertTrue(np.any(np.all(self.points == vertex, axis=1)))
        np.testing.assert_array_equal(normals, np.tile([0., 0., 1.], (len(v), 1)))
        self.assertEqual(stats["after"]["windingConflictEdges"], 0)
        self.assertEqual(stats["measuredOrientationBeforeTopologySplit"]["negativeSupportTriangles"], 0)
        self.assertEqual(stats["measuredOrientationBeforeTopologySplit"]["minimumMeanDot"], 1.)
        json.dumps(stats, allow_nan=False)

    def test_reject_all_long_edges_fails(self):
        with self.assertRaisesRegex(ValueError, "empty after rejecting [1-9]"):
            surface_mesh(self.points, self.normals, [.009], .005)

    def test_partial_long_edge_rejection_has_exact_audit_and_support(self):
        x, y = np.meshgrid([0., .01, .02, .04], [0., .01, .02])
        points = np.column_stack([x.ravel(), y.ravel(), np.zeros(x.size)])
        v, faces, _, stats = surface_mesh(points, np.tile([0., 0., 1.], (len(points), 1)),
                                         [.009, .025], .015)
        self.assertEqual(stats["before"]["triangles"], 12)
        self.assertEqual(stats["rejectedLongEdgeTriangles"], 4)
        self.assertEqual(stats["after"]["triangles"], 8)
        self.assertAlmostEqual(stats["after"]["surfaceAreaSquareMeters"], .02**2)
        self.assertEqual(stats["after"]["dimensionsMeters"], [.02, .02, 0.])
        for i, j in ((0, 1), (1, 2), (2, 0)):
            self.assertTrue(np.all(np.linalg.norm(v[faces[:, i]] - v[faces[:, j]], axis=1) <= .015))

    def test_native_observed_negative_normals_preserve_negative_winding(self):
        _, _, normals, stats = surface_mesh(self.points, -self.normals, [.009, .015], .02)
        np.testing.assert_array_equal(normals, np.tile([0., 0., -1.], (len(normals), 1)))
        self.assertAlmostEqual(stats["after"]["surfaceAreaSquareMeters"], .04**2)
        self.assertEqual(stats["measuredOrientationBeforeTopologySplit"]["minimumMeanDot"], 1.)

    def test_surface_config_and_input_validation(self):
        for radii in ([], [0.], [-1.], [np.inf], [[.01]]):
            with self.assertRaises(ValueError):
                surface_mesh(self.points, self.normals, radii, .02)
        for edge in (0., -1., np.nan, np.inf):
            with self.assertRaises(ValueError):
                surface_mesh(self.points, self.normals, [.01], edge)
        for normals in (np.zeros_like(self.normals), self.normals[:1], np.full_like(self.normals, np.inf)):
            with self.assertRaises(ValueError):
                surface_mesh(self.points, normals, [.01], .02)


if __name__ == "__main__":
    unittest.main()
