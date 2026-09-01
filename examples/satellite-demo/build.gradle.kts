import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Showcase for the satellite window archetype: two document windows sharing
// one floating inspector that anchors to a WindowPositioner, follows its
// parent, reparents between documents, and steps aside when a document is
// maximized or goes fullscreen.

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
    mainClass = "dev.nucleusframework.satellitedemo.MainKt"

    nativeDistributions {
        packageName = "satellite-demo"
        packageVersion = "1.0.0"
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "satellite-demo"
    }
}
