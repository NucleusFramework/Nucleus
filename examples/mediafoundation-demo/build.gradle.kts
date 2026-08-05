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
    mainClass = "dev.nucleusframework.samplemf.MainKt"

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "mediafoundation-demo"
        // The helper links Media Foundation and D3D11 at build time, both of
        // which ship with Windows — nothing extra is needed at runtime.
        buildArgs.add("-H:+AddAllCharsets")
    }
}
