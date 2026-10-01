plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":shared:scan-format"))
    testImplementation(libs.kotlin.testJunit)
}
