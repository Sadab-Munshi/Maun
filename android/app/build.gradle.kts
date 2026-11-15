plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Address of the deployed relay, e.g. https://maun.example.workers.dev
// Set with -Pmaun.relayUrl=... or the MAUN_RELAY_URL environment variable.
// When empty, the app asks for it on the welcome screen.
val relayUrl: String = providers.gradleProperty("maun.relayUrl")
    .orElse(providers.environmentVariable("MAUN_RELAY_URL"))
    .getOrElse("")

android {
    namespace = "org.maun.messenger"
    compileSdk = 37
    // Only used to strip debug symbols from the prebuilt native libraries
    // (libsignal, SQLCipher). Without it the APK is several times larger.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.maun.messenger"
        minSdk = 29 // Android 10: built-in Ogg/Opus recording, StrongBox API.
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"

        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")

        ndk {
            // Phones (arm) plus the standard emulator (x86_64).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // A fixed debug key, committed on purpose, so that every debug build
        // (local or CI) can be installed over the previous one without wiping
        // the app's keys. It protects nothing; release builds use a secret key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Shrinking and the signed release pipeline arrive in Milestone 6.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // libsignal uses newer Java library classes than Android 10 ships.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // A second copy of libsignal's native library, meant for its own tests.
            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            // libsignal's jar also carries desktop builds of its native library
            // (hundreds of MB) for JVM tests. They must not end up in the APK.
            excludes += setOf(
                "libsignal_jni*.so",
                "libsignal_jni*.dylib",
                "signal_jni*.dll",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

ksp {
    // Room writes each database version's layout here. The files are committed:
    // they are what future migrations are written and checked against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.libsignal.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)

    // QR codes: ZXing makes and reads them on the phone; CameraX supplies frames.
    implementation(libs.zxing.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Background delivery: WorkManager runs the fetch when the phone is woken;
    // UnifiedPush is how a push app on the phone (never Google's) does the waking.
    implementation(libs.work.runtime)
    implementation(libs.unifiedpush.connector)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    // Android's own org.json is only a stub on the JVM; tests need a real one.
    testImplementation(libs.json)
}
