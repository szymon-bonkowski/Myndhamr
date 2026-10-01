#include "myndhamr/foundation.hpp"
#include "myndhamr/scan/v1/foundation.pb.h"
#include <google/protobuf/io/coded_stream.h>
#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
#include <stdexcept>

namespace myndhamr {
std::string round_trip_record(std::string_view bytes) {
    if (bytes.size() > max_record_bytes) {
        throw std::invalid_argument("Foundation record exceeds 1 MiB bridge budget");
    }
    scan::v1::FoundationRecord record;
    if (!record.ParseFromArray(bytes.data(), static_cast<int>(bytes.size()))) {
        throw std::invalid_argument("Malformed FoundationRecord protobuf");
    }
    if (record.format_version() != 1) {
        throw std::invalid_argument("Unsupported FoundationRecord format_version (expected 1)");
    }
    const auto size = record.ByteSizeLong();
    if (size > max_record_bytes) {
        throw std::invalid_argument("Serialized FoundationRecord exceeds bridge budget");
    }
    std::string result(size, '\0');
    google::protobuf::io::ArrayOutputStream buffer(result.data(), static_cast<int>(size));
    google::protobuf::io::CodedOutputStream output(&buffer);
    output.SetSerializationDeterministic(true);
    if (!record.SerializeToCodedStream(&output) || output.HadError()) {
        throw std::runtime_error("FoundationRecord serialization failed");
    }
    return result;
}
}
