import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.kvth.hiroi"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kvth.hiroi"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // ML Kit's OCR model comes with native code; only phones (arm64) and the emulator (x86_64).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // On-device Japanese OCR with the model bundled. Closed source (Google APIs Terms of Service):
    // fine for building it yourself, but see the README before distributing APKs.
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    // Streaming JSON for dictionary collections, which can be hundreds of MB (Yomitan's Dexie export format)
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs of org.json; unit tests need the real thing.
    testImplementation("org.json:json:20240303")
}
