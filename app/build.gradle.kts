plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Push needs the Firebase project's own google-services.json (app/, never
// committed). Without it the plugin is not applied and the build still
// works; the app then has no FCM and runs on its MQTT connection alone.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.sphinx.peel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sphinx.peel"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // iOS keeps registering with the hub behind `PeelPushFlags.registerWithHub`
        // (off by default: the server keeps one push token per account).
        // Same here: -Ppeel.push.registerWithHub=true/false overrides; unset
        // it is on only when google-services.json is present.
        val register = (project.findProperty("peel.push.registerWithHub") as String?)?.toBoolean() ?: hasGoogleServices
        buildConfigField("boolean", "PUSH_REGISTER_WITH_HUB", register.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
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
