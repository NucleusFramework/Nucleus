// Probe contract, MVI base and the app-wide services every Nucleus Lab module shares.
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
    api(libs.compose.runtime)
    api(libs.compose.foundation)
    api(libs.metrox.viewmodel.compose)
    api(libs.coroutines.core)
    api(project(":core-runtime"))
    api(project(":aot-runtime"))
    api(project(":nucleus-application"))
    api(project(":decorated-window-tao"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
