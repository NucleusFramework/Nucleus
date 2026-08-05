plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(libs.coroutines.core)
    // OS-native Open dialog, so the sample can be pointed at a file without a
    // command line.
    implementation(libs.filekit.dialogs)
}

nucleus.application {
    mainClass = "dev.nucleusframework.sampleavf.MainKt"

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "avfoundation-demo"
        // The helper links AVFoundation and Metal at build time, both of which
        // ship with macOS — nothing extra is needed at runtime.
        buildArgs.add("-H:+AddAllCharsets")
    }
}
