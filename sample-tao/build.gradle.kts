import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("io.github.kdroidfilter.nucleus")
}

dependencies {
    implementation(project(":decorated-window-tao"))
    implementation(project(":core-runtime"))
    implementation(project(":graalvm-runtime"))
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
    mainClass = "io.github.kdroidfilter.sampletao.MainKt"

    // NOTE: -XstartOnFirstThread is intentionally NOT in `jvmArgs` here. The
    // Nucleus plugin forwards `jvmArgs` to native-image too, where the flag
    // makes no sense (binary is its own launcher, main() already on OS thread 0)
    // and would cause a runtime warning/error. We add it only to the JVM `:run`
    // task below.

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "sample-tao"
        march = providers.gradleProperty("nativeMarch").getOrElse("compatibility")
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
        )
    }

    nativeDistributions {
        targetFormats(TargetFormat.Dmg)
        appName = "Sample Tao"
        packageName = "SampleTao"
        packageVersion = "1.0.0"

        macOS {
            bundleID = "io.github.kdroidfilter.sampletao"
            // macOsSdkVersion defaults to "26.0" in the Nucleus plugin — vtool
            // patches the binary's loader command so macOS 26 enables the
            // Liquid-Glass / large-corner-radius treatment automatically.
            // Older macOS releases ignore the SDK marker.
        }
    }
}
