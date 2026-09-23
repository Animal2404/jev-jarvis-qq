import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------- signing
//
// A FIXED keystore committed to the repo, so every build — CI and local — is
// signed with the same key and can be installed OVER the previous version.
//
// Why this exists: the first CI builds fell back to the debug key, and GitHub
// runners are fresh VMs, so Gradle generated a NEW debug keystore on every run.
// Each release therefore had a different signature, and Android refuses to
// update an app whose signature changed ("应用未安装" / INSTALL_FAILED_UPDATE_
// INCOMPATIBLE) — the user had to uninstall, which also wiped their settings.
// Verified by hashing the APK signing block of two builds: they differed.
//
// This is a personal sideloaded app, so the keystore (and its password) living
// in the repo is a deliberate trade for reproducible updates. It is NOT a
// secret in any meaningful sense: it stops signature churn, it does not protect
// anything. If you fork this, replace it with your own.
val fixedKeystore = File(rootProject.projectDir, "keystore/release.jks")
val fixedProps = Properties().apply {
    put("storeFile", fixedKeystore.absolutePath)
    put("storePassword", "jevjarvis")
    put("keyAlias", "jev")
    put("keyPassword", "jevjarvis")
}

// An external properties file still wins when present (keeps a private key
// possible); otherwise the committed keystore is used when it exists.
val externalPropsFile = File(
    System.getenv("JEV_KEYSTORE_PROPS") ?: "H:/android/keys/jev-release.properties"
)
val externalProps = Properties().apply {
    if (externalPropsFile.isFile) FileInputStream(externalPropsFile).use { load(it) }
}
// File(...) rather than Gradle's file(): the latter parses a Windows "H:/..."
// path as a URL whose scheme is "H" and hard-fails the build on Linux. `File`
// is imported explicitly because a Kotlin DSL script resolves the bare name
// `java` to the Java plugin extension, not the package.
val useExternal = externalProps.isNotEmpty()
val signingProps = if (useExternal) externalProps else fixedProps
val haveSigning = useExternal || fixedKeystore.isFile

android {
    namespace = "com.jev.probe"

    // compileSdk 36 = Android 16. This is what lets the app run on Android 17
    // (API 37): an app compiled and targeted against 36 is forward-compatible —
    // targeting 37 is not required until Play demands it (Aug 2027) and AGP
    // 8.13 has no API 37 platform support to compile against anyway.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jev.probe"

        // Android 9 (API 28) through Android 17 (API 37).
        //
        // minSdk 28 vs the upstream 30: the only API-30 feature this app needs
        // is AccessibilityService.takeScreenshot, which is the OCR fallback for
        // apps whose node tree carries no text (Feishu). That call is now
        // runtime-gated in ScreenCapture.capture(), so on Android 9/10 the app
        // runs normally and only the OCR fallback reports "unsupported" instead
        // of crashing with NoSuchMethodError.
        minSdk = 28

        // targetSdk stays at 36 rather than tracking compileSdk up to 37: 36 is
        // the current Play requirement, and raising it only opts into stricter
        // behaviour (mandatory resizability, local-network permission) that this
        // app has no need for yet.
        targetSdk = 36
        versionCode = 8
        versionName = "1.7"

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // Only arm64-v8a is kept — the other three are dead weight (the app is
        // ~25MB, of which the OCR .so and models are the bulk).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Always created: signingProps is either the external file or the
        // committed keystore. `haveSigning` is false only if both are missing,
        // in which case the debug key is used and the APK will not update over
        // a previous one — so the build prints a loud warning.
        create("release") {
            storeFile = File(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

// Warn loudly if the build is about to produce an APK that cannot be installed
// over the previous one — that failure mode is invisible until the user tries.
if (!haveSigning) {
    logger.warn(
        "WARNING: no signing keystore found (neither keystore/release.jks nor " +
            "JEV_KEYSTORE_PROPS). The APK will NOT install over an earlier build."
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
