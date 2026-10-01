from __future__ import annotations

import json
import importlib.util
import os
import random
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw


WORKER = Path(__file__).resolve().parents[1] / "worker.py"
_SPEC = importlib.util.spec_from_file_location("colmap_worker", WORKER)
assert _SPEC is not None and _SPEC.loader is not None
_WORKER_MODULE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_WORKER_MODULE)


def _run_dir(root: Path, image_names: tuple[str, ...] = ("frame-1.png", "frame-2.png")) -> Path:
    run_dir = root / "run"
    (run_dir / "images").mkdir(parents=True)
    input_images = []
    for index, name in enumerate(image_names):
        image = Image.new("RGB", (64, 48), (70, 70, 70))
        draw = ImageDraw.Draw(image)
        draw.rectangle((8 + index, 8, 25 + index, 25), fill=(240, 240, 240))
        draw.line((8 + index, 25, 25 + index, 8), fill=(0, 0, 0), width=2)
        image.save(run_dir / "images" / name)
        input_images.append({
            "frameId": f"frame-{index + 1}", "name": name, "width": 64, "height": 48,
            "fx": 60.0, "fy": 60.0, "cx": 32.0, "cy": 24.0,
            "worldFromCameraColumnMajor": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
        })
    (run_dir / "input.json").write_text(json.dumps({"schemaVersion": 1, "images": input_images}), encoding="utf-8")
    (run_dir / "pairs.json").write_text(json.dumps({"pairs": [{
        "first": image_names[0], "second": image_names[1], "reason": "test"
    }]}), encoding="utf-8")
    return run_dir


def _invoke(run_dir: Path, extra_env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    return subprocess.run([sys.executable, str(WORKER), str(run_dir)], capture_output=True,
                          text=True, env=env, timeout=180)


def _textured_nonplanar_run(run_dir: Path) -> None:
    """Render stable random texture patches attached to a 3-D point cloud."""
    rng = random.Random(614)
    width, height = 800, 600
    fx = fy = 760.0
    cx, cy = width / 2, height / 2
    points = []
    for point_index in range(750):
        x = rng.uniform(-1.35, 1.35)
        y = rng.uniform(-0.9, 0.9)
        z = rng.uniform(3.0, 6.2)
        pattern = [[rng.choice((25, 235)) for _ in range(7)] for _ in range(7)]
        points.append((x, y, z, pattern, point_index))
    input_images = []
    for image_index, center_x in enumerate((-0.35, 0.0, 0.35)):
        image = Image.new("RGB", (width, height), (112, 112, 112))
        draw = ImageDraw.Draw(image)
        # Paint distant points first, so nearer points naturally occlude them.
        for x, y, z, pattern, point_index in sorted(points, key=lambda value: -value[2]):
            u = int(round(cx + fx * (x - center_x) / z))
            v = int(round(cy - fy * y / z))
            cell = max(1, int(round(fx * 0.075 / z / 7)))
            size = cell * 7
            left, top = u - size // 2, v - size // 2
            if left < 0 or top < 0 or left + size >= width or top + size >= height:
                continue
            for py, row in enumerate(pattern):
                for px, value in enumerate(row):
                    x0, y0 = left + px * cell, top + py * cell
                    draw.rectangle((x0, y0, x0 + cell - 1, y0 + cell - 1), fill=(value, value, value))
        name = f"view-{image_index}.png"
        image.save(run_dir / "images" / name)
        input_images.append({
            "frameId": f"f{image_index}", "name": name, "width": width, "height": height,
            "fx": fx, "fy": fy, "cx": cx, "cy": cy,
            "worldFromCameraColumnMajor": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0,
                                           center_x, 0, 0, 1],
        })
    (run_dir / "input.json").write_text(json.dumps({"schemaVersion": 1, "images": input_images}), encoding="utf-8")
    pairs = [{"first": f"view-{a}.png", "second": f"view-{b}.png", "reason": "synthetic-overlap"}
             for a, b in ((0, 1), (1, 2), (0, 2))]
    (run_dir / "pairs.json").write_text(json.dumps(pairs), encoding="utf-8")
    (run_dir / "worker-config.json").write_text(json.dumps({
        "threads": 1, "maxImageSize": 1200, "maxFeatures": 3000,
        "minNumMatches": 12, "initMinNumInliers": 12,
        "initMinTriAngleDegrees": 2.0, "absPoseMinNumInliers": 10, "seed": 614,
    }), encoding="utf-8")


class WorkerProcessTests(unittest.TestCase):
    def test_homogeneous_pose_export_is_column_major(self) -> None:
        class Pose:
            @staticmethod
            def matrix():
                return [[1, 0, 0, 2], [0, 1, 0, 3], [0, 0, 1, 4]]

        class ImageLike:
            @staticmethod
            def cam_from_world():
                return Pose()

        self.assertEqual(
            _WORKER_MODULE._column_major_world_to_optical(ImageLike()),
            [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 2, 3, 4, 1],
        )

    def test_rejects_unsafe_image_name_and_writes_failure_diagnostics(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = _run_dir(Path(temp))
            input_doc = json.loads((run_dir / "input.json").read_text())
            input_doc["images"][0]["name"] = "../escape.png"
            (run_dir / "input.json").write_text(json.dumps(input_doc))
            result = _invoke(run_dir)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            diagnostics = json.loads((run_dir / "adapter-diagnostics.json").read_text())
            self.assertEqual(diagnostics["status"], "failed")
            self.assertIn("safe PNG filename", diagnostics["error"]["message"])

    def test_rejects_pair_reference_outside_normalized_input(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = _run_dir(Path(temp))
            (run_dir / "pairs.json").write_text(json.dumps([{
                "first": "frame-1.png", "second": "missing.png", "reason": "bad-reference"
            }]))
            result = _invoke(run_dir)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            diag = json.loads((run_dir / "adapter-diagnostics.json").read_text())
            self.assertEqual(diag["status"], "failed")
            self.assertIn("absent from input.json", diag["error"]["message"])

    def test_fails_with_actionable_version_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            run_dir = _run_dir(root)
            fake_runtime = root / "fake-runtime"
            fake_runtime.mkdir()
            (fake_runtime / "pycolmap.py").write_text('__version__ = "3.12.6"\n', encoding="utf-8")
            result = _invoke(run_dir, {"PYTHONPATH": str(fake_runtime)})
            self.assertNotEqual(result.returncode, 0, result.stdout)
            diag = json.loads((run_dir / "adapter-diagnostics.json").read_text())
            self.assertEqual(diag["toolVersion"]["pycolmap"], "3.12.6")
            self.assertIn("must be exactly 3.13.0", diag["error"]["message"])

    def test_refuses_existing_generated_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = _run_dir(Path(temp))
            (run_dir / "colmap").mkdir()
            result = _invoke(run_dir)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            diag = json.loads((run_dir / "adapter-diagnostics.json").read_text())
            self.assertIn("refusing to overwrite", diag["error"]["message"])

    def test_textureless_images_report_insufficient_overlap(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = _run_dir(Path(temp))
            for path in (run_dir / "images").glob("*.png"):
                Image.new("RGB", (64, 48), (90, 90, 90)).save(path)
            result = _invoke(run_dir)
            self.assertNotEqual(result.returncode, 0)
            diag = json.loads((run_dir / "adapter-diagnostics.json").read_text())
            self.assertEqual(diag["status"], "failed")
            self.assertIn("no registered sparse model", diag["error"]["message"])
            self.assertEqual(diag["registeredImages"], 0)
            self.assertEqual(diag["featureExtraction"]["keypointCount"], 0)

    def test_disconnected_matches_preserve_separate_models(self) -> None:
        import shutil
        with tempfile.TemporaryDirectory() as temp:
            run_dir = _run_dir(Path(temp)); _textured_nonplanar_run(run_dir)
            document = json.loads((run_dir / "input.json").read_text())
            pairs = json.loads((run_dir / "pairs.json").read_text())
            originals = list(document["images"])
            for image in originals:
                copy = {**image, "name": "other-" + image["name"], "frameId": "other-" + image["frameId"]}
                shutil.copyfile(run_dir / "images" / image["name"], run_dir / "images" / copy["name"])
                document["images"].append(copy)
            pairs += [{**pair, "first": "other-" + pair["first"], "second": "other-" + pair["second"]} for pair in list(pairs)]
            (run_dir / "input.json").write_text(json.dumps(document))
            (run_dir / "pairs.json").write_text(json.dumps(pairs))
            result = _invoke(run_dir)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            diag = json.loads((run_dir / "adapter-diagnostics.json").read_text())
            self.assertEqual(diag["componentCount"], 2)
            self.assertEqual(diag["componentSizes"], [3, 3])
            self.assertEqual(diag["registeredImages"], 3)
            self.assertEqual(diag["registeredFraction"], .5)
            self.assertEqual(diag["verifiedGraph"]["componentCount"], 2)

    def test_tiny_procedural_nonplanar_scene_smoke(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run_dir = _run_dir(Path(temp))
            _textured_nonplanar_run(run_dir)
            result = _invoke(run_dir)
            self.assertEqual(result.returncode, 0, result.stdout + "\n" + result.stderr)
            diagnostics = json.loads((run_dir / "adapter-diagnostics.json").read_text())
            model = json.loads((run_dir / "colmap" / "raw-model.json").read_text())
            self.assertEqual(diagnostics["status"], "succeeded")
            self.assertEqual(diagnostics["registeredImages"], 3)
            self.assertEqual(diagnostics["registeredFraction"], 1.0)
            self.assertEqual(diagnostics["unregisteredFrameIds"], [])
            self.assertGreater(diagnostics["sparsePointCount"], 0)
            self.assertGreater(diagnostics["observationCount"], diagnostics["sparsePointCount"])
            self.assertEqual(diagnostics["verifiedGraph"]["componentCount"], 1)
            self.assertEqual(diagnostics["verifiedGraph"]["verifiedPairCount"], 3)
            self.assertEqual(diagnostics["toolVersion"]["pycolmap"], "3.13.0")
            self.assertIn("does not expose matching_options",
                          diagnostics["toolMetadata"]["matchingOptionsConstraint"])
            self.assertEqual(model["schemaVersion"], 1)
            self.assertEqual(len(model["cameras"]), 3)
            self.assertEqual(len(model["cameras"][0]["worldToOpticalColumnMajor"]), 16)
            model_dirs = sorted((run_dir / "colmap" / "models").iterdir())
            self.assertTrue(model_dirs)
            self.assertTrue((model_dirs[0] / "cameras.bin").is_file())
            self.assertTrue((model_dirs[0] / "images.txt").is_file())


if __name__ == "__main__":
    unittest.main()
