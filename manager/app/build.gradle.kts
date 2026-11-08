plugins {
    id("com.android.application") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.namalxhero.su"
    compileSdk = 34
    // Pinned so a local build and the manager-build.yml CI job use the
    // exact same NDK - avoids "works here, fails in CI" ABI drift.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.namalxhero.su"
        // Phase 1 decision: GKI 2.0 only -> Android 12 (API 31) and up.
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-phase3"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // TODO(Phase 6 / build pipeline): signing config wired up from
    // GitHub Actions secrets once the CI workflow is set up.
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.1")
}
