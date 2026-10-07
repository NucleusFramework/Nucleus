// Fixtures: programs that must own their process (exit on failure, Swing on the raw Tao loop,
// JVM flags the Lab must not run with), plus the runner probe that relaunches the Lab as one.
// Nucleus itself (nucleus-application, decorated-window-tao, core-runtime) comes through core.
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.metro)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        optIn.addAll(
            "dev.nucleusframework.window.ExperimentalNucleusApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(project(":examples:lab:designsystem"))
    // The partial-redraw fixture follows the OS appearance, as the Lab does.
    implementation(project(":darkmode-detector"))

    testImplementation(kotlin("test"))
}
