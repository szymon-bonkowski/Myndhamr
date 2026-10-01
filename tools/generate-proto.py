#!/usr/bin/env python3
"""Generate Java bindings from the pinned host compiler; never commit outputs."""
import pathlib
import subprocess
import shutil
import sys

protoc, source, output = map(pathlib.Path, sys.argv[1:])
if output.exists():
    shutil.rmtree(output)
output.mkdir(parents=True)
subprocess.run([str(protoc), f"--proto_path={source}", f"--java_out={output}",
                *map(str, sorted(source.rglob("*.proto")))], check=True)
