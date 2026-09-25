import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Demo and E2E fixture of screen-capture: lists the displays, captures a display, a region or
// the demo's own window, and previews the result. With SCREEN_CAPTURE_DEMO_SELFTEST=1 it draws
// a pixel-exact target pattern and checks every capture path against it, then exits with the
// number of failed checks (scripts/screen-capture-windows-e2e.ps1).

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":screen-capture"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(libs.coroutines.core)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

nucleus.application {
    mainClass = "screencapturedemo.MainKt"

    nativeDistributions {
        packageName = "ScreenCaptureDemo"
        packageVersion = "1.0.0"
    }
}
