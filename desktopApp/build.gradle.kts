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
    testImplementation(libs.kotlin.testJunit)
}
