import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
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

// Forwards the demo's and the partial redraw's switches from the Gradle
// command line (`-Dpartial.demo.scene=tour`, `-Dnucleus.tao.partialRedraw.verify=true`).
tasks.withType<JavaExec>().configureEach {
    listOf(
        "partial.demo.scene",
        "partial.demo.exitAfterSeconds",
        "partial.demo.maximized",
        "nucleus.tao.partialRedraw",
        "nucleus.tao.partialRedraw.debug",
        "nucleus.tao.partialRedraw.verify",
        "nucleus.tao.partialRedraw.verify.dump",
        "nucleus.tao.partialRedraw.tint",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

nucleus.application {
    mainClass = "com.example.partialredraw.MainKt"
    // Partial redraw is opt-in: patches Compose and turns the runtime on.
    nucleusOptimization { partialRedraw = true }
}
