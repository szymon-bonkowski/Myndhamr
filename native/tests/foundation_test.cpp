#include "myndhamr/foundation.hpp"
#include "myndhamr/scan/v1/foundation.pb.h"
#include <iostream>
#include <stdexcept>
#include <string>

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
void rejects(const std::string& bytes) {
    try { (void)myndhamr::round_trip_record(bytes); }
    catch (const std::invalid_argument&) { return; }
    throw std::runtime_error("Invalid record was accepted");
}
int main() {
    try {
        myndhamr::scan::v1::FoundationRecord record;
        record.set_format_version(1);
        record.set_project_id("synthetic-v1");
        record.set_source_timestamp_ns(9007199254740993LL);
        record.set_evidence(std::string("\0\xff\x01", 3));
        record.add_sample_ids(0);
        record.add_sample_ids(18446744073709551615ULL);
        auto input = record.SerializeAsString();
        input.append("\xa0\x06\x7b", 3); // Unknown field 100 = 123.
        auto output = myndhamr::round_trip_record(input);
        myndhamr::scan::v1::FoundationRecord parsed;
        require(parsed.ParseFromString(output), "Output failed to parse");
        require(parsed.project_id() == record.project_id(), "Project ID changed");
        require(parsed.source_timestamp_ns() == record.source_timestamp_ns(), "Timestamp lost precision");
        require(parsed.evidence() == record.evidence(), "Raw bytes changed");
        require(parsed.sample_ids(1) == record.sample_ids(1), "Unsigned ID changed");
        require(parsed.GetReflection()->GetUnknownFields(parsed).field_count() == 1, "Unknown field lost");
        require(output == myndhamr::round_trip_record(input), "Nondeterministic serialization");
        rejects("");
        rejects(std::string("\x80", 1));
        record.set_format_version(2);
        rejects(record.SerializeAsString());
        rejects(std::string(myndhamr::max_record_bytes + 1, 'x'));
        std::cout << "Native foundation: semantic/unknown-field/determinism/rejection tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
