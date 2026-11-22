import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Manual demo of the tray module: every Tray overload, dynamic menus/icons and TrayApp.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":tray"))
    implementation(project(":tray-app"))
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":decorated-window-material3"))
    implementation(project(":nucleus-application"))
    implementation(libs.compose.components.resources)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coroutines.core)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("dev.nucleusframework.window.ExperimentalNucleusApi")
    }
}

compose.resources {
    packageOfResClass = "dev.nucleusframework.tray.demo.resources"
    publicResClass = false
}

nucleus.application {
    mainClass = "dev.nucleusframework.tray.demo.TrayAppDemoKt"

    nativeDistributions {
        packageName = "TrayDemo"
        packageVersion = "1.0.0"
    }
}
