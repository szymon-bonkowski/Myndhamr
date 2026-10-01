# Golden fixture policy

`scan-v1/foundation.hex` is a hand-derived Protobuf wire-format record shared by the Kotlin and C++ foundation tests. It exercises format version `1`, project ID `synthetic-v1`, source timestamp `9007199254740993` nanoseconds, evidence bytes `00 ff 01`, and sample IDs `0` and `UINT64_MAX`. The timestamp deliberately exceeds the exact integer range of binary64 so accidental floating-point conversion is visible.

Golden fixtures are reviewed source data. If a schema change intentionally changes a fixture, update the schema and every affected reader/writer test in the same change, explain the byte-level reason in review, and verify cross-language round trips. Never regenerate a golden fixture automatically after a failing test or replace expected output with observed output just to make a test pass. Keep raw evidence fixtures immutable; derived expected products belong in separately named fixtures with their provenance and generator recorded.
