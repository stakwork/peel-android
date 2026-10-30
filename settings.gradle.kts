pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LiveKit's own audioswitch dependency (Bluetooth/speaker routing
        // for a call) is published only here, not on Maven Central.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Peel"
include(":app", ":sphinxrs")
