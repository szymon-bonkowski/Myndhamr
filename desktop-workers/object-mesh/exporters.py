"""Small, deterministic serializers for metric object meshes.

The input coordinates are already in capture-world: right-handed, +Y up, and
metres.  Exporters preserve those coordinates and the supplied face winding.
"""
from __future__ import annotations

import json
import os
import struct
import tempfile
from pathlib import Path
from typing import Iterable

import numpy as np


_FORMATS = ("ply", "obj", "glb")
_NORMAL_TOLERANCE = 1e-5
_UINT32_MAX = np.iinfo(np.uint32).max


def _validated_arrays(vertices: np.ndarray, faces: np.ndarray, normals: np.ndarray):
    vertices = np.asarray(vertices, dtype=np.float64)
    raw_faces = np.asarray(faces)
    normals = np.asarray(normals, dtype=np.float64)

    if vertices.ndim != 2 or vertices.shape[1:] != (3,) or vertices.shape[0] == 0:
        raise ValueError("vertices must be a non-empty Nx3 array")
    if normals.shape != vertices.shape:
        raise ValueError("normals must have the same Nx3 shape as vertices")
    if raw_faces.ndim != 2 or raw_faces.shape[1:] != (3,) or raw_faces.shape[0] == 0:
        raise ValueError("faces must be a non-empty Mx3 array")
    if (not np.issubdtype(raw_faces.dtype, np.integer)
            or np.issubdtype(raw_faces.dtype, np.bool_)):
        raise ValueError("faces must contain integer indices")
    if not np.isfinite(vertices).all():
        raise ValueError("vertices contain non-finite values")
    if not np.isfinite(normals).all():
        raise ValueError("normals contain non-finite values")
    if np.any(raw_faces < 0) or np.any(raw_faces >= len(vertices)):
        raise ValueError("face index is outside the vertex array")
    # glTF's unsigned-int index accessor is the selected binary representation.
    if np.any(raw_faces > _UINT32_MAX):
        raise ValueError("face index exceeds the GLB uint32 representation")

    lengths = np.linalg.norm(normals, axis=1)
    if not np.isfinite(lengths).all() or np.any(lengths <= 0):
        raise ValueError("normals must be finite non-zero unit vectors")
    if not np.allclose(lengths, 1.0, rtol=_NORMAL_TOLERANCE, atol=_NORMAL_TOLERANCE):
        raise ValueError("normals must be normalized before export")

    # A private contiguous copy prevents later caller mutation from changing a
    # serialization after its validation has completed.
    return (np.ascontiguousarray(vertices), np.ascontiguousarray(raw_faces, dtype=np.uint32),
            np.ascontiguousarray(normals))


def _ply_bytes(vertices: np.ndarray, faces: np.ndarray, normals: np.ndarray) -> bytes:
    lines = [
        "ply", "format ascii 1.0", "comment frame capture-world",
        "comment units meters", f"element vertex {len(vertices)}",
        "property double x", "property double y", "property double z",
        "property double nx", "property double ny", "property double nz",
        f"element face {len(faces)}", "property list uchar uint vertex_indices", "end_header",
    ]
    for vertex, normal in zip(vertices, normals):
        lines.append(" ".join(format(float(value), ".17g") for value in (*vertex, *normal)))
    lines.extend("3 " + " ".join(str(int(index)) for index in face) for face in faces)
    return ("\n".join(lines) + "\n").encode("ascii")


def _obj_bytes(vertices: np.ndarray, faces: np.ndarray, normals: np.ndarray) -> bytes:
    lines = ["# frame capture-world", "# units meters"]
    lines.extend("v " + " ".join(format(float(value), ".17g") for value in vertex)
                 for vertex in vertices)
    # OBJ indices are one-based.  Every vertex has a corresponding `vn`, so
    # face position and normal indices intentionally remain vertex-aligned.
    lines.extend("vn " + " ".join(format(float(value), ".17g") for value in normal)
                 for normal in normals)
    lines.extend("f " + " ".join(f"{int(index) + 1}//{int(index) + 1}" for index in face)
                 for face in faces)
    return ("\n".join(lines) + "\n").encode("ascii")


def _glb_bytes(vertices: np.ndarray, faces: np.ndarray, normals: np.ndarray) -> bytes:
    with np.errstate(over="ignore", invalid="ignore"):
        positions = np.asarray(vertices, dtype="<f4")
        gl_normals = np.asarray(normals, dtype="<f4")
    if not np.isfinite(positions).all() or not np.isfinite(gl_normals).all():
        raise ValueError("GLB geometry exceeds the finite float32 range")
    indices = np.asarray(faces, dtype="<u4").reshape(-1)

    # Each section begins on a 4-byte boundary as required by glTF.  The two
    # VEC3 float sections and the uint32 index section are all naturally aligned.
    pos_offset = 0
    normal_offset = positions.nbytes
    index_offset = normal_offset + gl_normals.nbytes
    binary = positions.tobytes(order="C") + gl_normals.tobytes(order="C") + indices.tobytes(order="C")

    gltf = {
        "asset": {
            "version": "2.0",
            "generator": "Myndhamr object-mesh exporter",
            "extras": {"units": "meters", "frame": "capture-world", "handedness": "right", "upAxis": "+Y"},
        },
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "capture-world mesh", "mesh": 0,
                   "matrix": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]}],
        "meshes": [{"name": "capture-world mesh", "primitives": [{
            "attributes": {"POSITION": 0, "NORMAL": 1},
            "indices": 2,
            "mode": 4,
        }]}],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(vertices), "type": "VEC3",
             "min": np.min(positions, axis=0).astype(float).tolist(),
             "max": np.max(positions, axis=0).astype(float).tolist()},
            {"bufferView": 1, "componentType": 5126, "count": len(normals), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5125, "count": len(indices), "type": "SCALAR",
             "min": [int(indices.min())], "max": [int(indices.max())]},
        ],
        "bufferViews": [
            {"buffer": 0, "byteOffset": pos_offset, "byteLength": positions.nbytes, "target": 34962},
            {"buffer": 0, "byteOffset": normal_offset, "byteLength": gl_normals.nbytes, "target": 34962},
            {"buffer": 0, "byteOffset": index_offset, "byteLength": indices.nbytes, "target": 34963},
        ],
        "buffers": [{"byteLength": len(binary)}],
    }
    json_chunk = json.dumps(gltf, separators=(",", ":"), allow_nan=False).encode("utf-8")
    json_chunk += b" " * ((-len(json_chunk)) % 4)
    binary += b"\0" * ((-len(binary)) % 4)
    total_length = 12 + 8 + len(json_chunk) + 8 + len(binary)
    return (struct.pack("<4sII", b"glTF", 2, total_length)
            + struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
            + struct.pack("<I4s", len(binary), b"BIN\0") + binary)


def _atomic_write_set(directory: Path, payloads: dict[str, bytes]) -> dict[str, Path]:
    """Stage every output, then replace the set with rollback on commit errors."""
    staged: dict[str, Path] = {}
    backups: dict[Path, Path] = {}
    committed: list[Path] = []
    succeeded = False
    destinations = {name: directory / f"mesh.{name}" for name in payloads}
    try:
        for name, payload in payloads.items():
            destination = destinations[name]
            fd, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".tmp", dir=directory)
            temporary = Path(temporary_name)
            staged[name] = temporary
            with os.fdopen(fd, "wb") as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())

        for destination in destinations.values():
            if destination.exists():
                fd, backup_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".bak", dir=directory)
                os.close(fd)
                backup = Path(backup_name)
                backup.unlink()
                os.replace(destination, backup)
                backups[destination] = backup

        for name, destination in destinations.items():
            os.replace(staged.pop(name), destination)
            committed.append(destination)
        succeeded = True
    except BaseException:
        for destination in reversed(committed):
            destination.unlink(missing_ok=True)
        for destination, backup in backups.items():
            if backup.exists():
                os.replace(backup, destination)
        raise
    finally:
        for temporary in staged.values():
            temporary.unlink(missing_ok=True)
        for backup in backups.values():
            # If rollback itself failed, keep the original file recoverable.
            if succeeded:
                backup.unlink(missing_ok=True)
    return destinations


def export_mesh(vertices: np.ndarray, faces: np.ndarray, normals: np.ndarray,
                directory: Path, formats: Iterable[str] = _FORMATS) -> dict[str, dict[str, object]]:
    """Write selected mesh formats and return their paths and byte sizes.

    Coordinates and input triangle order are preserved exactly in the text
    formats and rounded only to IEEE-754 float32 in GLB.  All validation and
    serialization happens before any output file is replaced.
    """
    vertices, faces, normals = _validated_arrays(vertices, faces, normals)
    try:
        selected = tuple(formats)
    except TypeError as error:
        raise ValueError("formats must be an iterable of format names") from error
    if not selected:
        raise ValueError("at least one output format is required")
    if not all(isinstance(format_name, str) for format_name in selected):
        raise ValueError("format names must be strings")
    if len(set(selected)) != len(selected):
        raise ValueError("formats must not contain duplicates")
    unsupported = [format_name for format_name in selected if format_name not in _FORMATS]
    if unsupported:
        raise ValueError(f"unsupported mesh format(s): {', '.join(map(str, unsupported))}")

    serializers = {"ply": _ply_bytes, "obj": _obj_bytes, "glb": _glb_bytes}
    payloads = {format_name: serializers[format_name](vertices, faces, normals)
                for format_name in selected}
    output_directory = Path(directory)
    output_directory.mkdir(parents=True, exist_ok=True)

    paths = _atomic_write_set(output_directory, payloads)
    return {format_name: {"path": path, "sizeBytes": path.stat().st_size}
            for format_name, path in paths.items()}
