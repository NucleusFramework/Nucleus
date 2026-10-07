// Workspace probes: the multi-window archetypes (Chrome-like tabs, satellites and docking,
// both composed), each opened as a Lab session that outlives navigation.
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
    // Specimen only: the Jewel tabs probe draws IntelliJ's own TabStrip over the same workspace.
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

    testImplementation(kotlin("test"))
}
