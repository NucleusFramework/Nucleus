import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fixture for the Windows hot-update E2E (scripts/e2e/windows-hot-update.ps1): the app shows its
// version on a version-coloured background and, when HOT_UPDATE_DEMO_FEED points at a loopback
// update feed, downloads the update and calls installAndRestart on its own.
//
// Build two versions with: ./gradlew :examples:hot-update-demo:packageNsis -PhotUpdateDemoVersion=1.1.0

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":updater-runtime"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(libs.coroutines.core)
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

val demoVersion = providers.gradleProperty("hotUpdateDemoVersion").getOrElse("1.0.0")

nucleus.application {
    mainClass = "hotupdatedemo.MainKt"

    nativeDistributions {
        packageName = "HotUpdateDemo"
        packageVersion = demoVersion
        targetFormats(TargetFormat.Nsis)

        windows {
            nsis {
                oneClick = true
                perMachine = false
                createDesktopShortcut = false
                createStartMenuShortcut = false
                runAfterFinish = false
            }
        }
    }
}
