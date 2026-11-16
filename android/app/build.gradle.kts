plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Address of the relay the app uses. A fresh install makes its ID there
// without asking. Building for another relay: -Pmaun.relayUrl=... or the
// MAUN_RELAY_URL environment variable. An empty value counts as not set (CI
// passes an empty variable when the repository has none). The word "ask"
// builds an app that asks for the address on a welcome screen instead.
val relayUrl: String = (providers.gradleProperty("maun.relayUrl").orNull ?: providers.environmentVariable("MAUN_RELAY_URL").orNull)
    ?.trim()
    .orEmpty()
    .ifEmpty { "https://maun.sadab.workers.dev" }
    .let { if (it == "ask") "" else it }

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
        versionCode = 16
        versionName = "0.13.0"

        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")

        ndk {
            // Phones (arm) plus the standard emulator (x86_64).
            // Phones only. Leaving out x86_64 (emulators) keeps the APK under 100 MB
            // now that the calling library is in it.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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

    testOptions {
        // The screen pictures are drawn by Android's own code running on the
        // build machine, which needs the app's resources and manifest.
        unitTests.isIncludeAndroidResources = true
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

// Pictures of the main screens, for looking at a change without a phone:
//     ./gradlew :app:testDebugUnitTest -Pmaun.screens=true
// writes them to app/build/screens. Without the flag those tests are left out,
// and with it nothing else runs.
val drawScreens: Boolean = providers.gradleProperty("maun.screens").isPresent

tasks.withType<Test>().configureEach {
    if (drawScreens) {
        systemProperty("roborazzi.test.record", "true")
        filter { includeTestsMatching("org.maun.messenger.screens.*") }
    } else {
        exclude("org/maun/messenger/screens/**")
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

    // Background delivery: WorkManager runs the fetch when the phone is woken.
    // UnifiedPush is how the waking is done: by a push app on the phone, or,
    // where there is none, by the push service of Google Play that most
    // phones already run. The second library asks Play services for a Web
    // Push address; it contains no Google code and needs no Firebase project.
    implementation(libs.work.runtime)
    implementation(libs.unifiedpush.connector)
    implementation(libs.unifiedpush.fcm)
    implementation(libs.webrtc)
    implementation(libs.androidx.core.telecom)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    // Android's own org.json is only a stub on the JVM; tests need a real one.
    testImplementation(libs.json)
    // Drawing the screens (see drawScreens above).
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
}
