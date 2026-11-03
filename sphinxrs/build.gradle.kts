plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "uniffi.sphinxrs"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
            // Note: a library's abiFilters do not constrain the app; see :app release buildType.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    // The @aar classifier carries JNA's own native library for Android; the
    // plain jar (what the version catalog alias resolves to) has no JNI glue.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}
