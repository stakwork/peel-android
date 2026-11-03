plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
        release {
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
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
