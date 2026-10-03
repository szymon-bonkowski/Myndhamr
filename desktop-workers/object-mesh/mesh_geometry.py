"""Conservative observed-surface geometry in capture-world metres.

Arrays hold row-wise points, but transformations use the documented column-vector
equation p_world = s R_world_sfm p_sfm + t_world_sfm. No axis conversion or
global winding correction is performed here. Native surface extraction and
edge-connected component analysis belong to Open3D.
"""
from __future__ import annotations

import operator

import numpy as np


def _open3d():
    try:
        import open3d
    except ImportError as error:
        raise RuntimeError("Open3D is required for object mesh geometry") from error
    return open3d


def _vectors(values, name, *, allow_empty=False):
    raw = np.asarray(values)
    if raw.ndim != 2 or raw.shape[1] != 3:
        raise ValueError(f"{name} must have shape N x 3")
    if raw.dtype.kind not in "fiu":
        raise ValueError(f"{name} must contain real numbers")
    result = np.asarray(raw, dtype=np.float64)
    if not np.isfinite(result).all():
        raise ValueError(f"{name} contains nonfinite values")
    if not allow_empty and not len(result):
        raise ValueError(f"{name} is empty")
    return result


def _mesh(vertices, faces):
    vertices = _vectors(vertices, "vertices", allow_empty=True)
    raw = np.asarray(faces)
    if raw.ndim != 2 or raw.shape[1] != 3 or raw.dtype.kind not in "iu":
        raise ValueError("faces must have shape M x 3 and an integer dtype")
    if len(raw) and (not len(vertices) or raw.min() < 0 or raw.max() >= len(vertices)):
        raise ValueError("faces contain invalid vertex indices")
    return vertices, np.asarray(raw, dtype=np.int64)


def _bounds(vertices):
    if not len(vertices):
        return None, None, 0.0
    low, high = vertices.min(axis=0), vertices.max(axis=0)
    with np.errstate(over="ignore", invalid="ignore"):
        dimensions = high - low
    diagonal = float(np.hypot.reduce(dimensions))
    if not np.isfinite(dimensions).all() or not np.isfinite(diagonal):
        raise ValueError("mesh extent exceeds finite metric range")
    return {"min": low.tolist(), "max": high.tolist()}, dimensions.tolist(), diagonal


def _crosses(vertices, faces, diagonal):
    # Scaling before cross products avoids underflow for tiny metric fixtures and
    # overflow for large ones; it never moves or rounds the stored vertices.
    if not len(faces) or diagonal == 0:
        return np.zeros((len(faces), 3), dtype=np.float64)
    a = (vertices[faces[:, 1]] - vertices[faces[:, 0]]) / diagonal
    b = (vertices[faces[:, 2]] - vertices[faces[:, 0]]) / diagonal
    result = np.cross(a, b)
    if not np.isfinite(result).all():
        raise ValueError("nonfinite triangle geometry")
    return result


def _components(vertices, faces):
    if not len(faces):
        return np.empty(0, dtype=np.int64), [], 0
    o3d = _open3d()
    mesh = o3d.geometry.TriangleMesh(
        o3d.utility.Vector3dVector(vertices), o3d.utility.Vector3iVector(faces))
    labels, counts, _ = mesh.cluster_connected_triangles()
    labels = np.asarray(labels, dtype=np.int64)
    details = []
    grouped = np.argsort(labels, kind="stable")
    offset = 0
    for index, count in enumerate(counts):
        selected = grouped[offset:offset + count]
        offset += count
        used = np.unique(faces[selected])
        bounds, dimensions, _ = _bounds(vertices[used])
        details.append({"index": index, "triangles": int(count), "vertices": len(used),
                        "firstTriangle": int(selected[0]), "boundsMeters": bounds,
                        "dimensionsMeters": dimensions})
    return labels, details, len(mesh.get_non_manifold_vertices())


def _edge_stats(faces):
    if not len(faces):
        return {"edges": 0, "boundaryEdges": 0, "nonManifoldEdges": 0,
                "windingConflictEdges": 0}
    edges = np.concatenate((faces[:, [0, 1]], faces[:, [1, 2]], faces[:, [2, 0]]))
    undirected = np.sort(edges, axis=1)
    unique, inverse, counts = np.unique(undirected, axis=0, return_inverse=True,
                                       return_counts=True)
    directions = np.where(edges[:, 0] < edges[:, 1], 1, -1)
    directed_sum = np.bincount(inverse, weights=directions, minlength=len(unique))
    # Two incident faces should traverse the shared edge in opposite directions.
    conflicts = (counts == 2) & (np.abs(directed_sum) == 2)
    return {"edges": len(unique), "boundaryEdges": int(np.count_nonzero(counts == 1)),
            "nonManifoldEdges": int(np.count_nonzero(counts > 2)),
            "windingConflictEdges": int(np.count_nonzero(conflicts))}


def validate_mesh(vertices, faces, normals=None):
    """Return JSON-safe diagnostics; malformed arrays/indices/NaN/Inf fail.

    Defective topology is reported rather than repaired. ``degenerateTriangles``
    counts exactly zero-area faces; near-zero areas are separately reported using
    4 float64 eps times the squared bounding diagonal. Components share edges,
    not merely a touching vertex. Empty meshes have null bounds.
    """
    vertices, faces = _mesh(vertices, faces)
    bounds, dimensions, diagonal = _bounds(vertices)
    crosses = _crosses(vertices, faces, diagonal)
    double_areas = np.hypot.reduce(crosses, axis=1)
    with np.errstate(over="ignore", under="ignore", invalid="ignore"):
        area = float(double_areas.sum() * diagonal * diagonal / 2)
    if not np.isfinite(area):
        raise ValueError("mesh surface area exceeds finite metric range")
    labels, details, nonmanifold_vertices = _components(vertices, faces)
    del labels
    stats = {"vertices": len(vertices), "triangles": len(faces),
             "components": len(details), "componentDetails": details,
             "boundsMeters": bounds, "dimensionsMeters": dimensions,
             "surfaceAreaSquareMeters": area,
             "degenerateTriangles": int(np.count_nonzero(double_areas == 0)),
             "nearDegenerateTriangles": int(np.count_nonzero(
                 (double_areas > 0) & (double_areas <= 8 * np.finfo(float).eps))),
             "duplicateVertices": len(vertices) - len(np.unique(vertices, axis=0)),
             "duplicateTriangles": len(faces) - len(np.unique(np.sort(faces, axis=1), axis=0)),
             "unreferencedVertices": len(vertices) - len(np.unique(faces)),
             "zeroNormalVertices": None,
             "nonManifoldVertices": nonmanifold_vertices,
             **_edge_stats(faces)}
    if normals is not None:
        normals = _vectors(normals, "normals", allow_empty=True)
        if normals.shape != vertices.shape:
            raise ValueError("normals must match the vertices shape")
        lengths = np.hypot.reduce(normals, axis=1)
        stats["zeroNormalVertices"] = int(np.count_nonzero(lengths == 0))
        stats["nonUnitNormalVertices"] = int(np.count_nonzero(
            ~np.isclose(lengths, 1.0, atol=1e-8, rtol=1e-8)))
    return stats


def _minimum_faces(value):
    try:
        if isinstance(value, (bool, np.bool_)):
            raise TypeError
        result = operator.index(value)
    except TypeError as error:
        raise ValueError("min_component_faces must be a nonnegative integer") from error
    if result < 0:
        raise ValueError("min_component_faces must be a nonnegative integer")
    return result


def _cleanup_mesh_arrays(vertices, faces, min_component_faces=0):
    minimum = _minimum_faces(min_component_faces)
    vertices, faces = _mesh(vertices, faces)
    before = validate_mesh(vertices, faces)
    if not len(vertices) or not len(faces):
        raise ValueError("mesh is empty")
    _, first, inverse = np.unique(vertices, axis=0, return_index=True, return_inverse=True)
    order = np.argsort(first)
    sorted_to_preserved = np.empty(len(order), dtype=np.int64)
    sorted_to_preserved[order] = np.arange(len(order))
    vertices = vertices[np.sort(first)].copy()
    faces = sorted_to_preserved[inverse[faces]]
    duplicate_vertices = before["vertices"] - len(vertices)
    _, _, diagonal = _bounds(vertices)
    nonzero = np.any(_crosses(vertices, faces, diagonal) != 0, axis=1)
    zero_area = int(np.count_nonzero(~nonzero))
    faces = faces[nonzero]
    _, keep = np.unique(np.sort(faces, axis=1), axis=0, return_index=True)
    duplicate_faces = len(faces) - len(keep)
    faces = faces[np.sort(keep)].copy()
    labels, details, _ = _components(vertices, faces)
    removed_components = [c for c in details if c["triangles"] < minimum]
    removed_labels = [c["index"] for c in removed_components]
    component_face_count = int(sum(c["triangles"] for c in removed_components))
    if removed_labels:
        faces = faces[~np.isin(labels, removed_labels)]
    if not len(faces):
        raise ValueError("cleanup produced an empty mesh")
    used = np.unique(faces)
    remap = np.full(len(vertices), -1, dtype=np.int64)
    remap[used] = np.arange(len(used))
    unreferenced = len(vertices) - len(used)
    vertices, faces = vertices[used].copy(), remap[faces]
    after = validate_mesh(vertices, faces)
    stats = {"before": before, "after": after, "minComponentFaces": minimum,
             "normalPolicy": "area-weighted preserved-winding; no global flip",
             "degeneratePolicy": "exact-zero-area only; near-zero diagnostic retained",
             "removals": {"duplicateVertices": duplicate_vertices,
                          "zeroAreaTriangles": zero_area, "duplicateTriangles": duplicate_faces,
                          "unreferencedVertices": unreferenced,
                          "componentTriangles": component_face_count,
                          "components": removed_components}}
    return vertices, faces, stats


def _vertex_normals(vertices, faces):
    _, _, diagonal = _bounds(vertices)
    normals = np.zeros_like(vertices)
    crosses = _crosses(vertices, faces, diagonal)
    for corner in range(3):
        np.add.at(normals, faces[:, corner], crosses)
    lengths = np.hypot.reduce(normals, axis=1)
    if not np.isfinite(lengths).all() or np.any(lengths == 0):
        raise ValueError("mesh has undefined area-weighted vertex normals; check winding")
    return normals / lengths[:, None]


def cleanup_mesh(vertices, faces, min_component_faces=0):
    """Exact cleanup with first-occurrence winding and area-weighted normals.

    This API does not cut topology or choose a global orientation. The surface
    pipeline separately cuts ambiguous adjacency before computing final normals.
    """
    vertices, faces, stats = _cleanup_mesh_arrays(vertices, faces, min_component_faces)
    normals = _vertex_normals(vertices, faces)
    stats["after"] = validate_mesh(vertices, faces, normals)
    return vertices, faces, normals, stats


def split_ambiguous_topology(vertices, faces):
    """Preserve every triangle corner and winding; open ambiguous connectivity.

    Every face incident to a same-direction shared edge receives private corners.
    On the remainder, separate disconnected vertex fans. With at most two faces
    per edge, each fan adjacency graph has degree <=2, so each connected fan is a
    manifold path or cycle. No vertex is moved, no face is dropped/flipped, and
    deliberate coincident seam vertices must never be welded afterwards.
    Nonmanifold edges are rejected rather than guessed at.
    """
    vertices, original_faces = _mesh(vertices, faces)
    before = validate_mesh(vertices, original_faces)
    if not len(original_faces) or before["degenerateTriangles"] or before["duplicateTriangles"]:
        raise ValueError("topology splitting requires nonempty cleaned nondegenerate triangles")
    if before["nonManifoldEdges"]:
        raise ValueError("topology splitting cannot interpret nonmanifold edges")
    faces = original_faces.copy()
    edges = np.concatenate((faces[:, [0, 1]], faces[:, [1, 2]], faces[:, [2, 0]]))
    triangle_ids = np.tile(np.arange(len(faces)), 3)
    unique, inverse, counts = np.unique(np.sort(edges, axis=1), axis=0,
                                         return_inverse=True, return_counts=True)
    direction = np.where(edges[:, 0] < edges[:, 1], 1, -1)
    signs = np.bincount(inverse, weights=direction, minlength=len(unique))
    conflict_edges = np.flatnonzero((counts == 2) & (np.abs(signs) == 2))
    isolated = np.unique(triangle_ids[np.isin(inverse, conflict_edges)])
    new_positions = []
    if len(isolated):
        corners = vertices[faces[isolated]].reshape(-1, 3)
        faces[isolated] = np.arange(len(vertices), len(vertices) + len(corners)).reshape(-1, 3)
        new_positions.extend(corners)
    interim_vertices = (np.vstack([vertices, new_positions]) if new_positions else vertices)
    o3d = _open3d()
    native = o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(interim_vertices),
                                      o3d.utility.Vector3iVector(faces))
    fan_vertices = np.asarray(native.get_non_manifold_vertices(), dtype=np.int64)
    fan_splits = 0
    if len(fan_vertices):
        # Shared-edge adjacency is computed once. Splitting a disconnected fan
        # at one vertex cannot break a valid shared edge's other endpoint fan.
        edges = np.concatenate((faces[:, [0, 1]], faces[:, [1, 2]], faces[:, [2, 0]]))
        _, inverse, counts = np.unique(np.sort(edges, axis=1), axis=0,
                                       return_inverse=True, return_counts=True)
        ordered = np.argsort(inverse, kind="stable")
        offsets = np.cumsum(np.r_[0, counts[:-1]])
        shared = np.flatnonzero(counts == 2)
        first, second = ordered[offsets[shared]], ordered[offsets[shared] + 1]
        neighbours = np.full(3 * len(faces), -1, dtype=np.int64)
        neighbours[first] = triangle_ids[second]
        neighbours[second] = triangle_ids[first]
        neighbours = neighbours.reshape(3, -1).T
        # Stable corner grouping provides incident triangles without an O(V*F) scan.
        flat = faces.reshape(-1)
        ordered_corners = np.argsort(flat, kind="stable")
        starts = np.r_[0, np.cumsum(np.bincount(flat, minlength=len(interim_vertices)))]
        for vertex in fan_vertices:
            incident = ordered_corners[starts[vertex]:starts[vertex + 1]] // 3
            remaining = set(int(t) for t in incident)
            first_fan = True
            while remaining:
                seed = min(remaining)
                remaining.remove(seed)
                pending, group = [seed], []
                while pending:
                    triangle = pending.pop()
                    group.append(triangle)
                    for neighbour in neighbours[triangle]:
                        if int(neighbour) in remaining:
                            remaining.remove(int(neighbour)); pending.append(int(neighbour))
                if first_fan:
                    first_fan = False
                else:
                    new_index = len(vertices) + len(new_positions)
                    new_positions.append(interim_vertices[vertex])
                    selected = faces[group].copy()
                    selected[selected == vertex] = new_index
                    faces[group] = selected
                    fan_splits += 1
    expanded = np.vstack([vertices, new_positions]) if new_positions else vertices.copy()
    used = np.unique(faces)
    remap = np.full(len(expanded), -1, dtype=np.int64)
    remap[used] = np.arange(len(used))
    result_vertices, result_faces = expanded[used].copy(), remap[faces]
    if not np.array_equal(result_vertices[result_faces], vertices[original_faces]):
        raise ValueError("topology splitting changed triangle positions/order/winding")
    normals = _vertex_normals(result_vertices, result_faces)
    after = validate_mesh(result_vertices, result_faces, normals)
    if after["windingConflictEdges"] or after["nonManifoldEdges"] or after["nonManifoldVertices"]:
        raise ValueError("topology splitting did not produce manifold open patches")
    audit = {"policy": "preserve all triangle corners/winding; isolate conflicting faces; split disconnected vertex fans; never weld seams",
             "before": before, "after": after,
             "conflictEdgeVertexIndicesBeforeRepair": unique[conflict_edges].tolist(),
             "isolatedFaceIndices": isolated.tolist(), "isolatedFaces": len(isolated),
             "disconnectedFanVertices": len(fan_vertices), "disconnectedFanSplits": fan_splits,
             "createdSeamVertices": len(new_positions),
             "discardedUnreferencedOriginalVertices": len(expanded) - len(used),
             "flippedTriangles": 0, "removedTriangles": 0, "movedVertices": 0,
             "triangleCornersExactlyPreserved": True,
             "orientationUncertainty": "local inconsistent/nonorientable adjacency is retained as separate open patches; no global sign is inferred"}
    return result_vertices, result_faces, normals, audit


def _unit_normals(normals, count):
    normals = _vectors(normals, "normals")
    if len(normals) != count:
        raise ValueError("normals must match points")
    # Normalize by largest absolute entry first to avoid overflowing huge normals.
    largest = np.abs(normals).max(axis=1)
    if np.any(largest == 0):
        raise ValueError("normals contain zero vectors")
    unit = normals / largest[:, None]
    return unit / np.hypot.reduce(unit, axis=1)[:, None]


def transform_cloud(points, normals, alignment):
    """Apply proper positive Sim(3) once; normals receive rotation only."""
    points = _vectors(points, "points")
    normals = _unit_normals(normals, len(points))
    try:
        scale = float(alignment["scale"])
        rotation = np.asarray(alignment["rotationWorldSfmRowMajor"], dtype=float)
        translation = np.asarray(alignment["translationWorldSfmMeters"], dtype=float)
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("malformed Sim(3) alignment") from error
    if rotation.shape != (9,) or translation.shape != (3,):
        raise ValueError("alignment requires row-major 9-entry rotation and 3-entry translation")
    rotation = rotation.reshape(3, 3)
    if not np.isfinite(scale) or scale <= 0 or not np.isfinite(rotation).all() or not np.isfinite(translation).all():
        raise ValueError("alignment must be finite with positive scale")
    if not np.allclose(rotation.T @ rotation, np.eye(3), atol=1e-8, rtol=0) or not np.isclose(np.linalg.det(rotation), 1, atol=1e-8, rtol=0):
        raise ValueError("alignment rotation must be proper and orthonormal")
    with np.errstate(over="ignore", invalid="ignore"):
        metric = scale * (points @ rotation.T) + translation
    if not np.isfinite(metric).all():
        raise ValueError("transformed points exceed finite metric range")
    return metric, _unit_normals(normals @ rotation.T, len(points))


def _measured_orientation(vertices, faces, points, observed_normals):
    """Diagnose source-normal agreement; never use it to choose face flips."""
    if np.array_equal(vertices, points):
        associated = observed_normals
        ambiguous_source_positions = 0
    else:
        # Exact lookup permits a backend to reorder observed source vertices.
        unique, first, inverse, counts = np.unique(points, axis=0, return_index=True,
                                                    return_inverse=True, return_counts=True)
        sums = np.zeros_like(unique)
        np.add.at(sums, inverse, observed_normals)
        means = sums / counts[:, None]
        row_type = np.dtype([("x", "<f8"), ("y", "<f8"), ("z", "<f8")])
        keys = np.ascontiguousarray(unique, dtype="<f8").view(row_type).reshape(-1)
        vertex_keys = np.ascontiguousarray(vertices, dtype="<f8").view(row_type).reshape(-1)
        index = np.searchsorted(keys, vertex_keys)
        if np.any(index >= len(unique)) or not np.array_equal(unique[index], vertices):
            raise ValueError("ball pivoting introduced an unobserved vertex position")
        associated = means[index]
        ambiguous_source_positions = int(np.count_nonzero(
            np.hypot.reduce(means[index], axis=1) <= 64 * np.finfo(float).eps))
    _, _, diagonal = _bounds(vertices)
    crosses = _crosses(vertices, faces, diagonal)
    lengths = np.hypot.reduce(crosses, axis=1)
    valid = lengths > 0
    support = np.einsum("ij,ij->i", crosses[valid], associated[faces[valid]].mean(axis=1)) / lengths[valid]
    return {"policy": "geometric face normal dot mean measured SfM-fusion vertex normal; diagnostic only; no orientation flip",
            "evaluatedTriangles": len(support), "degenerateTriangles": int(np.count_nonzero(~valid)),
            "negativeSupportTriangles": int(np.count_nonzero(support < 0)),
            "numericallyAmbiguousTriangles": int(np.count_nonzero(np.abs(support) <= 64 * np.finfo(float).eps)),
            "ambiguousSourcePositions": ambiguous_source_positions,
            "minimumMeanDot": float(support.min()) if len(support) else None,
            "medianMeanDot": float(np.median(support)) if len(support) else None}


def surface_mesh(points, normals, radii_meters, max_edge_meters, min_component_faces=0):
    """Ball-pivot measured normals; reject long edges without filling or smoothing."""
    points = _vectors(points, "points")
    normals = _unit_normals(normals, len(points))
    minimum = _minimum_faces(min_component_faces)
    radii = np.asarray(radii_meters, dtype=float)
    if radii.ndim != 1 or not len(radii) or not np.isfinite(radii).all() or np.any(radii <= 0):
        raise ValueError("ball-pivot radii must be nonempty finite positive metres")
    try:
        max_edge = float(max_edge_meters)
    except (ValueError, TypeError) as error:
        raise ValueError("max edge must be finite positive metres") from error
    if not np.isfinite(max_edge) or max_edge <= 0:
        raise ValueError("max edge must be finite positive metres")
    _bounds(points)
    o3d = _open3d()
    cloud = o3d.geometry.PointCloud()
    cloud.points = o3d.utility.Vector3dVector(points)
    cloud.normals = o3d.utility.Vector3dVector(normals)
    native = o3d.geometry.TriangleMesh.create_from_point_cloud_ball_pivoting(
        cloud, o3d.utility.DoubleVector(radii))
    vertices, faces = _mesh(np.asarray(native.vertices), np.asarray(native.triangles))
    before = validate_mesh(vertices, faces)
    edge_lengths = np.stack([np.hypot.reduce(vertices[faces[:, i]] - vertices[faces[:, j]], axis=1)
                             for i, j in ((0, 1), (1, 2), (2, 0))], axis=1)
    supported = np.all(edge_lengths <= max_edge, axis=1)
    rejected = int(np.count_nonzero(~supported))
    if not np.any(supported):
        raise ValueError(f"surface mesh is empty after rejecting {rejected} long-edge triangles "
                         f"from {len(faces)} ball-pivot triangles")
    orientation_support = _measured_orientation(vertices, faces[supported], points, normals)
    vertices, faces, cleanup = _cleanup_mesh_arrays(vertices, faces[supported], minimum)
    vertices, faces, normals, topology = split_ambiguous_topology(vertices, faces)
    return vertices, faces, normals, {
        "backend": "Open3D", "backendVersion": o3d.__version__,
        "algorithm": "ball-pivoting", "units": "meters", "frame": "capture-world",
        "radiiMeters": radii.tolist(), "maxEdgeMeters": max_edge,
        "inputPoints": len(points), "before": before,
        "rejectedLongEdgeTriangles": rejected, "cleanup": cleanup,
        "measuredOrientationBeforeTopologySplit": orientation_support,
        "topologyRepair": topology, "after": topology["after"]}
