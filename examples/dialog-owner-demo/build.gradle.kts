import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fixture for the dialog/owner E2E (scripts/windows-dialog-owner-e2e.ps1, #515): a main window with
// a focused text field and a DecoratedDialog opened on top of it.

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
    mainClass = "dialogownerdemo.MainKt"
}

// The E2E launches the app JVM itself, so it can find and stop it.
tasks.register("writeE2eClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    val out = layout.buildDirectory.file("e2e-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}
