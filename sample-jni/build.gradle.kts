import dev.nucleusframework.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework.nucleus")
}

dependencies {
    implementation(project(":decorated-window-jni"))
    implementation(project(":decorated-window-core"))
    implementation(project(":nucleus-application"))
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
    mainClass = "dev.nucleusframework.samplejni.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg)
        appName = "Sample JNI"
        packageName = "SampleJni"
        packageVersion = "1.0.0"
    }
}
