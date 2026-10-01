val nativeBuildDir = layout.buildDirectory.dir("native")
val nativeConfigure = tasks.register<Exec>("nativeConfigure") {
    inputs.files(fileTree("native") { exclude("**/build/**") })
    outputs.file(nativeBuildDir.map { it.file("CMakeCache.txt") })
    environment("JAVA_HOME", System.getProperty("java.home"))
    commandLine("cmake", "-S", file("native").absolutePath, "-B", nativeBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release")
}
val nativeBuild = tasks.register<Exec>("nativeBuild") {
    dependsOn(nativeConfigure)
    inputs.files(fileTree("native"), fileTree("shared/scan-format/src/main/proto"))
    outputs.files(nativeBuildDir.map { it.file("libmyndhamr_jni.so") },
        nativeBuildDir.map { it.file("myndhamr_native_tests") },
        nativeBuildDir.map { it.file("myndhamr_benchmark") },
        nativeBuildDir.map { it.file("myndhamr_align") },
        nativeBuildDir.map { it.file("myndhamr_geometry_tests") },
        nativeBuildDir.map { it.file("_deps/protobuf-build/protoc") })
    commandLine("cmake", "--build", nativeBuildDir.get().asFile.absolutePath, "--parallel", "4")
}
tasks.register<Exec>("nativeTest") {
    dependsOn(nativeBuild)
    commandLine("ctest", "--test-dir", nativeBuildDir.get().asFile.absolutePath, "--output-on-failure")
}
tasks.register<Exec>("benchmarkSmoke") {
    dependsOn(nativeBuild)
    commandLine(nativeBuildDir.get().file("myndhamr_benchmark").asFile.absolutePath)
}
tasks.register<Exec>("verifyProtoGeneration") {
    dependsOn(nativeBuild, ":shared:scan-format:generateProto")
    commandLine("python3", file("tools/verify-proto.py").absolutePath)
}
