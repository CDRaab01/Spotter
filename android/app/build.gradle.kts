import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

// Sift design-slop audit is wired in only when the sibling Sift repo is checked out next to this
// one (<parent>/{Spotter,Sift}); see settings.gradle.kts. CI builds a single repo, so Sift is
// absent and the audit (its test dependency + the DesignSlopTest source under src/test/sift) is
// skipped, keeping :app:testDebugUnitTest green. Locally it runs and gates as usual.
val siftEnabled = rootDir.parentFile?.parentFile?.resolve("Sift")?.exists() == true

android {
    namespace = "com.spotter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spotter"
        minSdk = 26
        targetSdk = 35
        // CI passes VERSION_CODE (the run number) so each signed release installs cleanly over the
        // previous one; defaults to the last shipped value for local/debug builds.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("VERSION_NAME") ?: "1.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String", "SERVER_URL",
            "\"${localProperties.getProperty("server.url", "https://spotter.dragonflymedia.org/")}\""
        )
    }

    signingConfigs {
        // A stable, committed key so every build — debug, local release, CI release — shares one
        // signing identity. New APKs install over the top of existing ones without Android
        // complaining about INSTALL_FAILED_UPDATE_INCOMPATIBLE. Password is not secret.
        create("stable") {
            storeFile = file("spotter-debug.keystore")
            storePassword = "spotter"
            keyAlias = "spotter"
            keyPassword = "spotter"
        }
        // CI's real release key, only when KEYSTORE_PATH is supplied in the environment.
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            // Prefer CI's release key; fall back to the stable committed key for local releases.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    // The Sift audit test (DesignSlopTest) lives in its own source root so it can be excluded —
    // along with its style.sift dependency — whenever the sibling Sift build isn't present.
    if (siftEnabled) {
        sourceSets.getByName("test").java.srcDir("src/test/sift")
    }
}

// Forward Roborazzi's -P flags (record/verify/compare) into the forked unit-test JVM as the
// system properties it actually reads.
tasks.withType<Test>().configureEach {
    listOf(
        "roborazzi.test.record",
        "roborazzi.test.verify",
        "roborazzi.test.compare",
    ).forEach { key ->
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Retrofit + OkHttp + Serialization
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Confetti (celebration moments)
    implementation(libs.konfetti.compose)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    // JVM screenshot tests (Robolectric + Roborazzi) — no device/KVM required.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Contributes the empty ComponentActivity to the debug manifest so Robolectric/Compose
    // test rules can resolve a host activity.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)

    // Sift design-slop audit (DesignSlopTest). Resolved from the included Sift build via
    // composite-build dependency substitution (see settings.gradle.kts). Added only when the
    // sibling Sift checkout is present, matching the src/test/sift source set above.
    if (siftEnabled) {
        testImplementation("style.sift:sift-compose:0.1.0")
    }
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}
