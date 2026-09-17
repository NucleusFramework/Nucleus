import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Showcase for the window chrome API (issue #129): full-window content
// layouts, glass regions, drag areas and the platform window controls, each
// driven live from the app itself.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":graalvm-runtime"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation(libs.compose.material.icons.extended)
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
    mainClass = "dev.nucleusframework.scaffolddemo.MainKt"

    nativeDistributions {
        packageName = "window-scaffold-demo"
        packageVersion = "1.0.0"
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "window-scaffold-demo"
    }
}
