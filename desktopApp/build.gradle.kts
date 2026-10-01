plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "io.github.szymonbonkowski.myndhamr.MainKt"
}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:project-store"))
    testImplementation(libs.kotlin.testJunit)
}

tasks.register<JavaExec>("generateSparseFixture") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.szymonbonkowski.myndhamr.reconstruction.FixtureProjectMainKt"
    // -PfixtureRender=... -PfixtureScan=...; tooling is kept outside the production CLI.
    args(providers.gradleProperty("fixtureRender").orElse("build/sparse-render").get(),
        providers.gradleProperty("fixtureScan").orElse("build/sparse-fixture.scan3d").get())
}

tasks.register<JavaExec>("sparseBenchmark") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.szymonbonkowski.myndhamr.reconstruction.SparseBenchmarkMainKt"
}
