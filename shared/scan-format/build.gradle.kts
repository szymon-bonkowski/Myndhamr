plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
}

kotlin { jvmToolchain(21) }
val nativeConfig = rootProject.file("native/dependencies.cmake").readText()
val protobufVersion = Regex("set\\(MYNDHAMR_PROTOBUF_VERSION ([^)]+)\\)")
    .find(nativeConfig)!!.groupValues[1]

dependencies {
    api("com.google.protobuf:protobuf-java:4.$protobufVersion")
    testImplementation(libs.kotlin.testJunit)
}

val generatedJava = layout.buildDirectory.dir("generated/source/proto/main/java")
val generateProto = tasks.register<Exec>("generateProto") {
    dependsOn(":nativeBuild")
    inputs.files(fileTree("src/main/proto"))
    inputs.file(rootProject.file("tools/generate-proto.py"))
    inputs.file(rootProject.file("native/dependencies.cmake"))
    inputs.file(rootProject.layout.buildDirectory.file("native/_deps/protobuf-build/protoc"))
    outputs.dir(generatedJava)
    // protoc itself is built on the host from the same pin as the C++ runtime.
    commandLine("python3", rootProject.file("tools/generate-proto.py").absolutePath,
        rootProject.layout.buildDirectory.file("native/_deps/protobuf-build/protoc").get().asFile.absolutePath,
        file("src/main/proto").absolutePath, generatedJava.get().asFile.absolutePath)
}
sourceSets.main { java.srcDir(generatedJava) }
tasks.named("compileJava") { dependsOn(generateProto) }
tasks.named("compileKotlin") { dependsOn(generateProto) }

tasks.test {
    dependsOn(":nativeBuild")
    systemProperty("java.library.path", rootProject.layout.buildDirectory.dir("native").get().asFile.absolutePath)
    systemProperty("myndhamr.fixtures", rootProject.file("tests/fixtures/scan-v1").absolutePath)
}
