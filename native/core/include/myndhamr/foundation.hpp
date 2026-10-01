#pragma once
#include <cstddef>
#include <string>
#include <string_view>

namespace myndhamr {
inline constexpr std::size_t max_record_bytes = 1024 * 1024;
// Owned result. Input is borrowed only for this call. Throws invalid_argument
// for corrupt, unsupported or oversized records; never changes source evidence.
std::string round_trip_record(std::string_view bytes);
}
