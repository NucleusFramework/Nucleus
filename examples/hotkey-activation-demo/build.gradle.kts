import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fixture for the Linux global-hotkey activation E2E (scripts/linux-hotkey-activation-e2e.sh, #739):
// a quick-entry window, hidden until a global hotkey brings it up with the portal's activation token.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":global-hotkey"))
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
    mainClass = "hotkeydemo.MainKt"
}

// The E2E launches the app itself, inside a systemd scope that gives it a portal app id.
tasks.register("writeE2eClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    val out = layout.buildDirectory.file("e2e-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}
