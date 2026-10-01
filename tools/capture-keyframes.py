#!/usr/bin/env python3
"""Acceptance-only ADB requests through the recorder's existing manual path.

This does no frame scoring or selection. Each request must become a persisted
keyframe before the next request; raw projects are never edited or removed.
"""
import argparse
import json
import math
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="Explicit authorized ADB device serial")
    parser.add_argument("output", type=Path, help="New directory for host-side logs")
    parser.add_argument("--frames", type=int, default=90)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--startup-timeout", type=float, default=30.0)
    parser.add_argument("--keyframe-timeout", type=float, default=8.0)
    parser.add_argument("--package", default="io.github.szymonbonkowski.myndhamr")
    args = parser.parse_args()
    if not args.serial or args.frames < 1 or any(
        not math.isfinite(v) or v <= 0
        for v in (args.interval, args.startup_timeout, args.keyframe_timeout)
    ):
        parser.error("Serial, frame count and timing values must be valid and positive")
    args.output.mkdir(parents=True, exist_ok=False)
    with (args.output / "requests.jsonl").open("x", encoding="utf-8") as log:
        def record(kind, **values):
            log.write(json.dumps({"kind": kind, "hostUnixNs": time.time_ns(), **values}) + "\n")
            log.flush()

        def adb(*command):
            result = subprocess.run(
                ["adb", "-s", args.serial, *command], capture_output=True,
                text=True, timeout=15, check=True,
            )
            return result.stdout.strip()

        def status():
            # The optional status task can overlap this read. Retry an incomplete
            # JSON snapshot; never treat a command's broadcast result as an image.
            for attempt in range(3):
                raw = adb("shell", "run-as", args.package, "cat", "files/capture-status.json")
                try:
                    value = json.loads(raw)
                    record("status", value=value)
                    return value
                except json.JSONDecodeError:
                    if attempt == 2:
                        raise
                    time.sleep(0.1)

        def command(value):
            reply = adb("shell", "am", "broadcast", "-a", args.package + ".CAPTURE_COMMAND",
                        "-p", args.package, "--es", "capture_command", value)
            record("command", command=value, reply=reply)

        def healthy(value):
            if value.get("failure") or value.get("failureCallbackErrors", 0):
                raise RuntimeError(f"Recorder failure: {value}")

        project = None
        final = None
        failure = None
        requested = 0
        startup_issued = False
        try:
            if adb("get-state") != "device":
                raise RuntimeError("ADB device is not authorized")
            before = status()
            if before["state"] in ("STARTING", "RECORDING", "STOPPING"):
                raise RuntimeError("An existing capture is active; refusing to interrupt it")
            record("configuration", serial=args.serial, package=args.package,
                   frames=args.frames, intervalSeconds=args.interval,
                   startupTimeoutSeconds=args.startup_timeout,
                   keyframeTimeoutSeconds=args.keyframe_timeout, baseline=before)
            adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            startup_issued = True
            adb("shell", "am", "start", "-W", "-n", args.package + "/.MainActivity",
                "--es", "capture_command", "start")
            deadline = time.monotonic() + args.startup_timeout
            while True:
                value = status()
                if value.get("project") and value["project"] != before.get("project"):
                    project = value["project"]
                    healthy(value)
                    if value["state"] == "FAILED":
                        raise RuntimeError(f"Capture startup failed: {value}")
                    if value["state"] == "RECORDING" and value["tracking"] == "TRACKING":
                        break
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"No tracked new capture within startup timeout: {value}")
                time.sleep(0.5)
            if value["keyframes"] != 0:
                raise RuntimeError("New capture already contains manual keyframes")
            print(f"Recording {project}: requesting {args.frames} keyframes", flush=True)
            next_request = time.monotonic()
            for target in range(1, args.frames + 1):
                time.sleep(max(0, next_request - time.monotonic()))
                value = status()
                healthy(value)
                if value["state"] != "RECORDING" or value["project"] != project:
                    raise RuntimeError(f"Capture changed before request: {value}")
                if value["keyframes"] != target - 1 or value["pendingKeyframe"]:
                    raise RuntimeError(f"Unexpected manual keyframe state: {value}")
                command("keyframe")
                requested += 1
                deadline = time.monotonic() + args.keyframe_timeout
                while True:
                    value = status()
                    healthy(value)
                    if value["state"] != "RECORDING" or value["project"] != project:
                        raise RuntimeError(f"Capture changed after request: {value}")
                    if value["keyframes"] == target and not value["pendingKeyframe"]:
                        break
                    if value["keyframes"] > target or time.monotonic() >= deadline:
                        raise RuntimeError(f"Manual request did not persist exactly one image: {value}")
                    time.sleep(0.15)
                next_request = max(next_request + args.interval, time.monotonic())
                if target % 10 == 0 or target == args.frames:
                    print(f"Persisted {target}/{args.frames}; tracking={value['tracking']}", flush=True)
        except (Exception, KeyboardInterrupt) as error:
            failure = f"{type(error).__name__}: {error}"
        finally:
            if project is not None:
                try:
                    selected = status()
                    if selected["project"] != project:
                        raise RuntimeError("Selected project changed; refusing to stop another capture")
                    command("stop")
                    deadline = time.monotonic() + 30
                    while True:
                        final = status()
                        healthy(final)
                        if final["project"] != project:
                            raise RuntimeError("Selected project changed during finalization")
                        if final["state"] == "COMPLETED" and final["queue"] == 0 and final["queuedBytes"] == 0:
                            break
                        if final["state"] in ("FAILED", "INTERRUPTED") or time.monotonic() >= deadline:
                            raise RuntimeError(f"Capture did not finalize cleanly: {final}")
                        time.sleep(0.25)
                    if final["keyframes"] != requested or final["pendingKeyframe"]:
                        raise RuntimeError(f"Final request/image count mismatch: {final}")
                except Exception as error:
                    failure = (failure + "; " if failure else "") + f"Finalization: {error}"
            elif startup_issued:
                failure = (failure + "; " if failure else "") + "Startup was issued but project ownership is unknown; inspect device status before continuing"
            result = {"format": "myndhamr-manual-keyframe-acceptance-v1",
                      "serial": args.serial, "project": project, "requested": requested,
                      "target": args.frames, "intervalSeconds": args.interval,
                      "startupCommandIssued": startup_issued,
                      "finalStatus": final, "failure": failure}
            (args.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result), flush=True)
        return 1 if failure else 0


if __name__ == "__main__":
    raise SystemExit(main())
