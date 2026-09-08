import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A right-to-left book reader whose every pane is a satellite: the navigation
// panels layered on the right, each with its own width and splitter, the
// translation on the left, the commentaries under the text — the pane tree of
// a split-pane reader, drawn by one DockLayout with the reader's own 1 dp
// dividers and hover headers, every pane undockable into its own window.

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
    mainClass = "dev.nucleusframework.readerdockdemo.MainKt"

    nativeDistributions {
        packageName = "reader-dock-demo"
        packageVersion = "1.0.0"
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "reader-dock-demo"
    }
}
