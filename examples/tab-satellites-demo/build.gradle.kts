import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The two multi-window archetypes composed: Chrome-like tabs where every tab
// owns its own satellites. One `SatelliteWorkspace` per document, whose only
// member is the window the document's tab is composed in — so the palettes
// belong to the tab and follow it from window to window.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":decorated-window-tao"))
    implementation(project(":decorated-window-material3"))
    implementation(project(":nucleus-application"))
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":graalvm-runtime"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
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
    mainClass = "dev.nucleusframework.tabsatellitesdemo.MainKt"

    nativeDistributions {
        packageName = "tab-satellites-demo"
        packageVersion = "1.0.0"
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "tab-satellites-demo"
    }
}
