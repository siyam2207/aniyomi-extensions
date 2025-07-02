dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven(url = "https://jitpack.io") // ✅ Needed for GitHub-hosted libs like injekt-core
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") // ✅ Important for plugin resolution from GitHub
    }
}
