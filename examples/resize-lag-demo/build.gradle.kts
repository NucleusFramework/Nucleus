import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fixture for the macOS resize-lag E2E (scripts/macos-resize-lag-e2e, #953): a window whose content marks
// its left rail, right edge and bottom edge, so a frame captured mid-resize shows whether the content on
// screen was laid out for the window size on screen.

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
    mainClass = "resizelagdemo.MainKt"
}
