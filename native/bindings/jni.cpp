#include "myndhamr/foundation.hpp"
#include <jni.h>
#include <exception>
#include <new>
#include <stdexcept>
#include <string>

namespace {
void raise(JNIEnv* env, const char* type, const char* message) {
    const auto cls = env->FindClass(type);
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_szymonbonkowski_myndhamr_scan_NativeFoundation_roundTrip(
    JNIEnv* env, jobject, jbyteArray input) {
    if (input == nullptr) {
        raise(env, "java/lang/IllegalArgumentException", "FoundationRecord bytes cannot be null");
        return nullptr;
    }
    const auto length = env->GetArrayLength(input);
    if (static_cast<std::size_t>(length) > myndhamr::max_record_bytes) {
        raise(env, "java/lang/IllegalArgumentException", "Foundation record exceeds 1 MiB bridge budget");
        return nullptr;
    }
    try {
        std::string bytes(static_cast<std::size_t>(length), '\0');
        env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        const auto result = myndhamr::round_trip_record(bytes);
        auto output = env->NewByteArray(static_cast<jsize>(result.size()));
        if (output == nullptr) return nullptr;
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()),
                               reinterpret_cast<const jbyte*>(result.data()));
        return env->ExceptionCheck() ? nullptr : output;
    } catch (const std::invalid_argument& error) {
        raise(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::bad_alloc&) {
        raise(env, "java/lang/OutOfMemoryError", "Native FoundationRecord allocation failed");
    } catch (const std::exception& error) {
        raise(env, "java/lang/IllegalStateException", error.what());
    } catch (...) {
        raise(env, "java/lang/IllegalStateException", "Unexpected native FoundationRecord failure");
    }
    return nullptr;
}
