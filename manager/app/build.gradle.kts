plugins {
    id("com.android.application") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("com.google.devtools.ksp") version "1.9.24-1.0.20"
}

android {
    namespace = "com.namalxhero.su"
    compileSdk = 34

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}

/*
 * Phase 3 continued fix: kernel/include/su_uapi.h is the single source
 * of truth for the kernel<->userspace ABI (ioctl numbers, struct
 * layout). Instead of hand-duplicating it in su_native.cpp (a drift
 * risk flagged in Phase 3's design doc), copy it verbatim into the
 * native source tree before every native build.
 */
val uapiHeaderSrc = rootProject.file("../kernel/include/su_uapi.h")
val uapiHeaderDestDir = file("src/main/cpp/generated")

tasks.register<Copy>("syncUapiHeader") {
    description = "Copies kernel/include/su_uapi.h into cpp/generated/ so " +
        "su_native.cpp includes the kernel module's real ABI header " +
        "instead of a hand-duplicated copy."
    from(uapiHeaderSrc)
    into(uapiHeaderDestDir)
    onlyIf {
        if (!uapiHeaderSrc.exists()) {
            logger.warn("su_uapi.h not found at $uapiHeaderSrc - skipping sync; " +
                "su_native.cpp will use whatever copy is already in cpp/generated/.")
        }
        uapiHeaderSrc.exists()
    }
}

tasks.matching { it.name.startsWith("externalNativeBuild") || it.name == "preBuild" }
    .configureEach { dependsOn("syncUapiHeader") }
