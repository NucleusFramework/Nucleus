// The Lab's single visual language: Jewel (IntelliJ's Int UI) underneath, dense readouts, probe
// layout. Probes see only this module's API; Jewel stays an implementation detail.
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(project(":examples:lab:core"))
    // MaterialSpecimen only: probes declare Material themselves for the specimens they own.
    implementation(libs.compose.material3)

    implementation(project(":decorated-window-jewel"))
    val jewelExclusions =
        Action<ExternalModuleDependency> {
            exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
        }
    implementation(libs.jewel.int.ui.standalone, jewelExclusions)
    // Jewel 0.39+ IntUiTheme needs IconManager/DefaultIconManager from these.
    implementation(libs.intellij.icons)
    implementation(libs.intellij.icons.api)
    implementation(libs.intellij.icons.impl)
    // Jewel's StandalonePlatformCursorController uses JNA at runtime.
    implementation(libs.jna.jpms)
}
