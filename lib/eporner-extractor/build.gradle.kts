plugins {
    id("lib-android")
}

android {
    namespace = "eu.kanade.tachiyomi.lib.epornerextractor"
}

dependencies {
    implementation(project(":lib:i18n"))
    implementation(project(":lib:playlist-utils"))
}
