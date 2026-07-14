plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.psychojelly.joancues"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.psychojelly.joancues"
        minSdk = 26          // Android 8.0+ — covers any modern tablet
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // Sideload-friendly: no shrinking so nothing gets stripped.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Tiny embedded HTTP server — the Kotlin twin of proxy/server.py.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
