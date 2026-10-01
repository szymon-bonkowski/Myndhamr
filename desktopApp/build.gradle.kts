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
