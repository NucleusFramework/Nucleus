import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("io.github.kdroidfilter.nucleus")
}

dependencies {
    implementation(project(":decorated-window-jni"))
    implementation(project(":decorated-window-core"))
    implementation(project(":sample-shared"))
    implementation(project(":core-runtime"))
    implementation(compose.desktop.currentOs)
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
    mainClass = "io.github.kdroidfilter.samplejni.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg)
        appName = "Sample JNI"
        packageName = "SampleJni"
        packageVersion = "1.0.0"
    }
}
