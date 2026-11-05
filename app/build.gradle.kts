plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Push needs the Firebase project's own google-services.json (app/, never
// committed). Without it the plugin is not applied and the build still
// works; the app then has no FCM and runs on its MQTT connection alone.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) apply(plugin = "com.google.gms.google-services")

// Read a value from a Gradle property (-PNAME=...) or the environment; blank = unset.
fun propOrEnv(name: String): String? =
    (providers.gradleProperty(name).orNull ?: System.getenv(name))?.takeIf { it.isNotBlank() }

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
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
        // PUSH_REGISTER_WITH_HUB: whether the app registers its FCM token with
        // the hub (the server keeps one token per app id). Release builds: off
        // unless -Ppeel.push.registerWithHub=true. Debug builds: on when
        // google-services.json exists. -P always overrides.
        buildConfigField("boolean", "PUSH_REGISTER_WITH_HUB", "false")
        // Own app id on the hub (NOT applicationId, which equals the iOS bundle id).
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
            buildConfigField("boolean", "PUSH_REGISTER_WITH_HUB", (registerOverride ?: hasGoogleServices).toString())
        }
        release {
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
}
