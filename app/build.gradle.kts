plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.runeshift.nuzbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.runeshift.nuzbridge"
        minSdk = 30          // AccessibilityService.takeScreenshot(displayId) needs API 30
        targetSdk = 36
        versionCode = 82
        versionName = "0.82"
        // The AYN Thor (Snapdragon) is arm64-only; shipping the other ABIs'
        // OCR libs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }
    // A FIXED signing key, committed deliberately.
    //
    // Debug builds are otherwise signed with whatever ~/.android/debug.keystore
    // exists on the build machine — which meant an APK built here and one built
    // by GitHub Actions had different keys, and Android refused the update with
    // a signature conflict. That is exactly what happened moving from a
    // hand-delivered APK to Obtainium.
    //
    // This is a dedicated key for THIS app, not the personal debug key, so
    // publishing it in a public repo cannot affect anything else that is built
    // on this machine. It grants no privilege beyond identifying updates to
    // NuzBridge, which is not distributed through any store.
    signingConfigs {
        getByName("debug") {
            storeFile = file("nuzbridge-debug.keystore")
            storePassword = "nuzbridge"
            keyAlias = "nuzbridge"
            keyPassword = "nuzbridge"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Bundled Latin model (~4 MB in-APK) — no Play services dependency, works
    // on any device incl. de-Googled handhelds.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // OcrParser is pure logic + org.json; this lets it run under plain JUnit
    // on the desktop (the only part of the app testable without the device).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
