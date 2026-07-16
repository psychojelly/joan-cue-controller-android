import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing (optional): create app/keystore.properties (gitignored) with
//   storeFile=joan-release.keystore
//   storePassword=...
//   keyAlias=joan
//   keyPassword=...
// Generate the keystore once with:
//   keytool -genkeypair -v -keystore app/joan-release.keystore -alias joan \
//     -keyalg RSA -keysize 2048 -validity 10000
// Without the file, release builds fall back to unsigned (sideload debug
// builds keep working as before).
val keystoreProps = Properties().apply {
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.psychojelly.joancues"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.psychojelly.joancues"
        minSdk = 24          // Android 7.0+ — covers Fire HD 8 (2018, Fire OS 6) and newer
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file("app/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sideload-friendly: no shrinking so nothing gets stripped.
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
