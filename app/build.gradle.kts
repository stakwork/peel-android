plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sphinx.peel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sphinx.peel"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
