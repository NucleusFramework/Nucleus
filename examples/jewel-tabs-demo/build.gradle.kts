import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The tab workspace wearing IntelliJ's own tab chrome: Jewel's `TabStrip` and
// `TabData.Editor` render the strip, the Nucleus `TabWorkspace` owns what the
// tabs are and which window holds each of them.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":decorated-window-jewel"))
    implementation(project(":nucleus-application"))
    val jewelExclusions =
        Action<ExternalModuleDependency> {
            exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
        }
    implementation(libs.jewel.int.ui.standalone, jewelExclusions)
    // Jewel 0.39+ IntUiTheme needs IconManager/DefaultIconManager from these.
    implementation(libs.intellij.icons)
    implementation(libs.intellij.icons.api)
    implementation(libs.intellij.icons.impl)
    // Jewel's StandalonePlatformCursorController uses JNA at runtime.
    implementation(libs.jna.jpms)
}

// decorated-window-jewel is a JVM 25 module (Jewel's own target), so anything
// linking against it follows.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

// Those classes come out as class file 69, so the app has to *run* on a 25 JVM
// too — and the Gradle JVM is often older. Resolved through a toolchain rather
// than a hard-coded path.
val jvm25 =
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        .map { it.metadata.installationPath.asFile.absolutePath }

nucleus.application {
    mainClass = "dev.nucleusframework.jeweltabsdemo.MainKt"
    javaHome = jvm25.get()

    nativeDistributions {
        packageName = "jewel-tabs-demo"
        packageVersion = "1.0.0"
    }
}
