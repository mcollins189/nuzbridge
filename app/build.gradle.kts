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
        versionCode = 60
        versionName = "0.60"
        // The AYN Thor (Snapdragon) is arm64-only; shipping the other ABIs'
        // OCR libs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
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
