import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fixture for #958: a second Wayland window opened and closed while the first window's swap thread is
// parked in eglSwapBuffers, waiting on a frame callback the compositor withholds from a hidden window.

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
    mainClass = "swapstalldemo.MainKt"
}

// The E2E launches the app JVM itself, so it can find and stop it.
tasks.register("writeE2eClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    val out = layout.buildDirectory.file("e2e-classpath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}
