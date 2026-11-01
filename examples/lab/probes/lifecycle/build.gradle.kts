// Lifecycle probes: deep links, single instance, start at login, SMAppService, scheduled
// tasks, the hang watchdog, and the app's identity as the runtime sees it.
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinxSerialization)
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
    implementation(project(":autolaunch"))
    implementation(project(":service-management-macos"))
    implementation(project(":scheduler"))
    implementation(project(":scheduler-testing"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
