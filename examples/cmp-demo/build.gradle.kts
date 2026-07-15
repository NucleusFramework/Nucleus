import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.kotlin.dsl.implementation
import org.gradle.kotlin.dsl.project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinComposePlugin)
    id("dev.nucleusframework")
}

// AGP's JdkImageTransform runs `jlink` against the toolchain JDK. JDK 25 is not
// yet supported by AGP 8.10.1 (the core-for-system-modules.jar transform fails),
// so we pin sample-cmp to JDK 21 — Gradle auto-provisions it via foojay.
kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":nucleus-application"))
                implementation(project(":decorated-window-core"))

                implementation(project(":decorated-window-tao"))

            }
        }
    }
}

android {
    namespace = "com.example.samplecmp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.samplecmp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

nucleus.application {
    mainClass = "com.example.samplecmp.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb, TargetFormat.Rpm)
        cleanupNativeLibs = true
        packageName = "SampleCmp"
        packageVersion = "1.0.0"
        compressionLevel = CompressionLevel.Maximum
        homepage = "https://github.com/KdroidFilter/NucleusDemo"

        linux {
            debMaintainer = "KDroidFilter <dev@kdroidfilter.com>"
        }

    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.ORACLE
        imageName = "cmp-sample"
        optimization = NativeImageOptimization.SIZE
    }
}
