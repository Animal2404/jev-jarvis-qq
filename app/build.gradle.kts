import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads a properties file kept OUTSIDE the repo
// (storeFile / storePassword / keyAlias / keyPassword). Override the path with
// the JEV_KEYSTORE_PROPS env var. Without it (the CI case) the release build
// falls back to the debug key so the published APK stays installable.
//
// Resolved through java.io.File, NOT Gradle's file(): the latter parses a
// Windows path like "H:/android/keys/..." as a URL whose scheme is "H" and
// hard-fails the entire build on Linux ("Cannot convert URL ... to a file").
val releasePropsFile = java.io.File(
    System.getenv("JEV_KEYSTORE_PROPS") ?: "H:/android/keys/jev-release.properties"
)
val releaseProps = Properties().apply {
    if (releasePropsFile.isFile) FileInputStream(releasePropsFile).use { load(it) }
}

android {
    namespace = "com.jev.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jev.probe"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // The target phone (and every phone this can run on: minSdk 30) is
        // arm64, so keep only that one — the other three are dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Fall back to the debug key when no release keystore is present
            // (CI), so the artifact is always installable. Sideloading an APK
            // signed with the debug key is fine; an unsigned one is not.
            signingConfig = if (releaseProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
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

// No API keys are baked into the APK on purpose: the artifact is public, and
// anything inside it can be extracted. Each user pastes their own keys into the
// settings page once; they live only in the device's app-private storage.

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
