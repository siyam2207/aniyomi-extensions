plugins {
    id("lib-android")
}

dependencies {
    // Optional JSON parsing if you need later
    implementation(libs.jsunpacker) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }

    // Required for HLS / DASH parsing
    implementation(project(":lib:playlist-utils"))

    // If you need shared utilities like synchrony
    // implementation(project(":lib:synchrony"))
}
