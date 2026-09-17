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
    mainClass = "dev.nucleusframework.samplegst.MainKt"

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "gstreamer-demo"
        // The helper dlopens nothing: it links GStreamer at build time, so the image
        // needs those libraries at runtime like the JVM run does.
        buildArgs.add("-H:+AddAllCharsets")
    }
}
