#include "myndhamr/foundation.hpp"
#include "myndhamr/scan/v1/foundation.pb.h"
#include <chrono>
#include <iostream>
#include <stdexcept>

int main() {
    try {
        myndhamr::scan::v1::FoundationRecord record;
        record.set_format_version(1);
        record.set_project_id("benchmark-smoke");
        record.set_evidence("measured-evidence");
        const auto input = record.SerializeAsString();
        constexpr int warmup = 100;
        constexpr int iterations = 1000;
        for (int i = 0; i < warmup; ++i) (void)myndhamr::round_trip_record(input);
        std::size_t checksum = 0;
        const auto start = std::chrono::steady_clock::now();
        for (int i = 0; i < iterations; ++i) {
            const auto output = myndhamr::round_trip_record(input);
            if (output != input) throw std::runtime_error("Benchmark correctness failure");
            checksum += output.size();
        }
        const auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - start).count();
        std::cout << "{\"benchmark\":\"protobuf_round_trip\",\"iterations\":" << iterations
                  << ",\"elapsed_ns\":" << ns << ",\"checksum\":" << checksum << "}\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
