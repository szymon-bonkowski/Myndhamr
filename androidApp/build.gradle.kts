import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":shared:domain"))
    implementation(project(":shared:scan-format"))
    implementation(project(":shared:project-store"))
    implementation(libs.arcore)
    testImplementation(libs.kotlin.testJunit)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.test.runner)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "io.github.szymonbonkowski.myndhamr"
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = rootProject.file("native/CMakeLists.txt")
            version = "3.30.5"
        }
    }
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.szymonbonkowski.myndhamr"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DMYNDHAMR_HOST_PROTOC=${rootProject.layout.buildDirectory.file("native/_deps/protobuf-build/protoc").get().asFile.absolutePath}",
                    "-DBUILD_TESTING=OFF",
                    "-DMYNDHAMR_BUILD_BENCHMARKS=OFF"
                )
                targets += "myndhamr_jni"
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
// Cross-compilation uses the pinned host protoc, never an Android executable.
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(":nativeBuild")
}
