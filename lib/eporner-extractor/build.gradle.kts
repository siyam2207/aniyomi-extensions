plugins {
    id("lib-android")
}

dependencies {
    implementation(project(":lib:playlist-utils"))
    compileOnly(project(":lib:i18n"))
    compileOnly(project(":lib:network"))
}
