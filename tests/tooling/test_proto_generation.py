"""Prevent obsolete generated classes from surviving schema removal."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROTOC = ROOT / 'build/native/_deps/protobuf-build/protoc'


class ProtoGenerationTest(unittest.TestCase):
    def test_schema_removal_removes_obsolete_classes(self):
        with tempfile.TemporaryDirectory() as temp:
            source = pathlib.Path(temp) / 'proto'
            output = pathlib.Path(temp) / 'generated'
            source.mkdir()
            keep = source / 'keep.proto'
            keep.write_text('syntax="proto3"; option java_multiple_files=true; message Keep {}')
            old = source / 'obsolete.proto'
            old.write_text('syntax="proto3"; option java_multiple_files=true; message Obsolete {}')
            command = ['python3', str(ROOT / 'tools/generate-proto.py'),
                       str(PROTOC), str(source), str(output)]
            subprocess.run(command, check=True)
            self.assertTrue((output / 'Obsolete.java').exists())
            old.unlink()
            subprocess.run(command, check=True)
            self.assertTrue((output / 'Keep.java').exists())
            self.assertFalse((output / 'Obsolete.java').exists())


if __name__ == '__main__':
    unittest.main()
