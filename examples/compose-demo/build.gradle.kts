import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization
import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

// This demo is a verbatim port of an external sample (bitsycore/compose-desktop-native);
// its many screens don't follow the repo's ktlint/detekt style, so linting is disabled
// here (detekt is already skipped via the root's demoProjects set).
tasks.matching { it.name.contains("ktlint", ignoreCase = true) }.configureEach {
    enabled = false
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Nucleus windowing: Tao backend + Material 3 decorated chrome.
    implementation(project(":decorated-window-tao"))
    implementation(project(":decorated-window-material3"))
    implementation(project(":decorated-window-core"))
    implementation(project(":nucleus-application"))
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":system-color"))

    // File management via FileKit (OS-native Open / Save / directory pickers).
    implementation(libs.filekit.dialogs)

    // Navigation 3 showcase screen (Compose 1.11-compatible 1.1.x line).
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)

    // Dynamic Material 3 theming from a seed color.
    implementation(libs.materialkolor)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "demo.generated.resources"
}

val releaseVersion =
    System
        .getenv("RELEASE_VERSION")
        ?.removePrefix("v")
        ?.takeIf { it.isNotBlank() && it.first().isDigit() }
        ?: "1.0.0"

val nativePackageVersion = releaseVersion.substringBefore("-")

nucleus.application {
    mainClass = "com.example.composedemo.MainKt"

    buildTypes {
        release {
            proguard {
                version = "7.8.1"
                isEnabled = true
                optimize = false
            }
        }
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.ORACLE
        imageName = "compose-demo"
        optimization = NativeImageOptimization.SIZE
    }

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        appName = "Compose Desktop Demo"
        packageName = "ComposeDesktopDemo"
        packageVersion = nativePackageVersion
        compressionLevel = CompressionLevel.Maximum
    }
}
