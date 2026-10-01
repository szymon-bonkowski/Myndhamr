#!/usr/bin/env python3
"""Compare two independent generations with Gradle/CMake generated bindings."""
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parent.parent
protoc = root / 'build/native/_deps/protobuf-build/protoc'
source = root / 'shared/scan-format/src/main/proto'
proto = source / 'myndhamr/scan/v1/foundation.proto'
with tempfile.TemporaryDirectory() as tmp:
    for run in ('a', 'b'):
        out = pathlib.Path(tmp) / run
        out.mkdir()
        subprocess.run([str(protoc), f'--proto_path={source}', f'--java_out={out}',
                        f'--cpp_out={out}', str(proto)], check=True)
    a, b = (pathlib.Path(tmp) / run for run in ('a', 'b'))
    java_root = root / 'shared/scan-format/build/generated/source/proto/main/java'
    cpp_root = root / 'build/native/generated'
    expected = {p.relative_to(a) for p in a.rglob('*') if p.is_file()}
    actual = {p.relative_to(java_root) for p in java_root.rglob('*.java')}
    actual |= {p.relative_to(cpp_root) for p in cpp_root.rglob('*') if p.suffix in ('.cc', '.h')}
    assert expected == actual, f'Stale or missing bindings: {expected ^ actual}'
    for path in sorted(a.rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(a)
        assert path.read_bytes() == (b / relative).read_bytes(), relative
        existing = root / ('build/native/generated' if path.suffix in ('.cc', '.h')
                           else 'shared/scan-format/build/generated/source/proto/main/java') / relative
        assert path.read_bytes() == existing.read_bytes(), f'Stale bindings: {existing}'
print('Java/C++ generation is reproducible and matches build outputs')
