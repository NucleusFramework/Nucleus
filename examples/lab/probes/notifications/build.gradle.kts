// Notification probes: the cross-platform DSL, then each platform's own API in full.
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.metro)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        optIn.add("dev.nucleusframework.window.ExperimentalNucleusApi")
    }
}

dependencies {
    implementation(project(":examples:lab:designsystem"))
    implementation(project(":notification-common"))
    implementation(project(":notification-macos"))
    implementation(project(":notification-windows"))
    implementation(project(":notification-linux"))
    implementation(project(":freedesktop-icons"))

    testImplementation(kotlin("test"))
}
