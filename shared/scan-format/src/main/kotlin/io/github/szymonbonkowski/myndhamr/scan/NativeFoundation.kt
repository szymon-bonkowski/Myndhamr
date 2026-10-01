package io.github.szymonbonkowski.myndhamr.scan

/** Coarse byte-array bridge. C++ copies input and returns a new owned array. */
object NativeFoundation {
    init {
        System.loadLibrary("myndhamr_jni")
    }

    /** Throws IllegalArgumentException for invalid/version-incompatible input. */
    external fun roundTrip(bytes: ByteArray): ByteArray
}
