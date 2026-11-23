plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Read a value from a Gradle property (-PNAME=...) or the environment; blank = unset.
fun propOrEnv(name: String): String? =
    (providers.gradleProperty(name).orNull ?: System.getenv(name))?.takeIf { it.isNotBlank() }

// "Peel Dev", opt in: with PEEL_DEV_APP=true (-PPEEL_DEV_APP=true, ~/.gradle/gradle.properties, or the environment) a debug
// build is com.sphinx.peel.dev, named "Peel Dev", so it installs beside a release Peel instead of replacing it (as iOS's dev Peel
// does). Unset, nothing changes. README, "Peel Dev beside the release app".
val devApp = propOrEnv("PEEL_DEV_APP")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true

// Push needs the Firebase project's own google-services.json (app/, never
// committed). Without it the plugin is not applied and the build still
// works; the app then has no FCM and runs on its MQTT connection alone.
// Peel Dev never applies it (the file names com.sphinx.peel, and the plugin
// fails a build whose app id it has no client for).
val hasGoogleServices = file("google-services.json").exists() && !devApp
if (hasGoogleServices) apply(plugin = "com.google.gms.google-services")

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
// Self-update (docs/phases/53-in-app-updates.md): only a build made by the release workflow (a supplied VERSION_CODE, release
// type) can update in place. Debug builds (versionCode 1, maybe a .dev id) and unversioned local builds never check or prompt.
val versionSupplied = propOrEnv("VERSION_CODE") != null
// Test override: -Ppeel.update.force=true makes a debug build check on launch too (it still can't install over a release: other
// key, versionCode 1; the signature check says so), and -Ppeel.update.repo=owner/name follows another repo's releases.
val updateForce = propOrEnv("peel.update.force")?.equals("true", ignoreCase = true) == true
val updateRepo = propOrEnv("peel.update.repo")?.takeIf { Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+").matches(it) } ?: "stakwork/peel-android"

val hasReleaseSigning = listOf(
    releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword
).all { it != null }

android {
    namespace = "com.sphinx.peel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sphinx.peel"
        minSdk = 26
        targetSdk = 36
        versionCode = propOrEnv("VERSION_CODE")?.toInt() ?: 1
        versionName = propOrEnv("VERSION_NAME") ?: "1.0.0"
        // The launcher's name (the manifest's android:label): Peel, or "Peel Dev" for an opted-in debug build.
        manifestPlaceholders["appLabel"] = "@string/app_name"
        // PUSH_REGISTER_WITH_HUB: whether the app registers its FCM token with
        // the hub (the server keeps one token per app id). Release builds: off
        // unless -Ppeel.push.registerWithHub=true. Debug builds: on when
        // google-services.json exists. -P always overrides.
        buildConfigField("boolean", "PUSH_REGISTER_WITH_HUB", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Own app id on the hub (NOT applicationId, which equals the iOS bundle id).
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "PUSH_APP_ID", "\"com.sphinx.peel.android\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // Per build type (not per requested task), so `build`, `assemble`, IDE
        // and bundle builds all get the right value. -P always overrides.
        val registerOverride = (project.findProperty("peel.push.registerWithHub") as String?)?.toBoolean()
        debug {
            buildConfigField("boolean", "UPDATES_ENABLED", updateForce.toString())
            buildConfigField("boolean", "PUSH_REGISTER_WITH_HUB", (registerOverride ?: hasGoogleServices).toString())
            if (devApp) {
                applicationIdSuffix = ".dev"
                manifestPlaceholders["appLabel"] = "Peel Dev"
            }
        }
        release {
            buildConfigField("boolean", "UPDATES_ENABLED", versionSupplied.toString())
            buildConfigField("boolean", "PUSH_REGISTER_WITH_HUB", (registerOverride ?: false).toString())
            // Release ships arm64-v8a only: x86_64 is only for emulators, and the
            // JNA aar's other ABIs are dropped too. Debug stays unfiltered so debug
            // builds still run on x86_64 emulators. (A library's abiFilters, e.g. in
            // :sphinxrs, do not constrain the app, so the filter is set here.)
            ndk {
                abiFilters += "arm64-v8a"
            }
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Compose UI tests under Robolectric need the merged manifest and resources.
            isIncludeAndroidResources = true
            // Print each failing test and its stack trace in the log (CI shows only "There were failing tests" otherwise).
            all { test ->
                test.testLogging {
                    events("failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStackTraces = true
                    showCauses = true
                    showStandardStreams = false
                }
            }
        }
    }

    sourceSets {
        // The strut wallet vectors live with the JVM tests; the instrumented signing test reads the same file.
        getByName("androidTest").resources.srcDir("src/test/resources")
    }

    packaging {
        // Netty (pulled in by the HiveMQ MQTT client) ships these in
        // several of its jars; Android's packaging can only keep one.
        resources.excludes += "META-INF/INDEX.LIST"
        resources.excludes += "META-INF/io.netty.versions.properties"
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric's sandboxed classloader (ServerConfigTest, StorageTest)
    // can leave the JVM's java.security provider list in a state that
    // breaks a later, plain test in the same fork — PinBoxTest's PBKDF2
    // started throwing NoSuchAlgorithmException only once a Robolectric
    // test ran first in the same JVM. One fork per test class avoids it.
    forkEvery = 1
}

dependencies {
    implementation(project(":sphinxrs"))
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.okhttp)
    implementation(libs.livekit.android)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.activity.compose)
    // Newer than what firebase-messaging pulls in transitively (lint: InvalidFragmentVersionForActivityResult).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Instrumented tests (app/src/androidTest): need a device or emulator; see docs/phases/40-test-coverage.md.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
