import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.NativeImageMarch
import dev.nucleusframework.desktop.application.dsl.TargetFormat
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
    implementation(project(":examples:shared"))
    implementation(project(":core-runtime"))
    implementation(compose.desktop.currentOs)
    // Extract-and-load native lib (issue #317): zstd-kmp ships libzstd-kmp.{dylib,so,dll} inside
    // its JAR and loads a temp-extracted copy via System.load(temp). Adding it here + the Pkg
    // store format below activates the sandboxed pipeline so the marker/shim rewrite is exercised
    // end-to-end on macOS.
    implementation(libs.zstd.kmp.jvm)
}

java {
    // Bumped to 21 so the Foreign Function & Memory API (`java.lang.foreign`)
    // — preview in JDK 21, stable in JDK 22 — is available for the SwiftUI
    // sample tab's bridge. Lower modules stay on 17.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// FFM is a preview API on JDK 21 — every JVM that runs this sample (JVM
// `run` task plus any test/exec the plugin spawns) needs `--enable-preview`.
// We don't put it in `nucleus.application.jvmArgs` because that list is
// forwarded to native-image too, where the flag is unknown and fails the
// build. Adding it here only hits the JavaExec tasks.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

nucleus.application {
    mainClass = "dev.nucleusframework.sampletao.MainKt"

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
        // Leave unset for the per-platform default; -PnativeMarch=native overrides it locally.
        providers.gradleProperty("nativeMarch").orNull?.let {
            march = NativeImageMarch.valueOf(it.uppercase())
        }
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
        )
    }

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Pkg)
        compressionLevel = CompressionLevel.Maximum
        appName = "Sample Tao"
        packageName = "SampleTao"
        packageVersion = "1.0.0"

        macOS {
            bundleID = "dev.nucleusframework.sampletao"
            // macOsSdkVersion defaults to "26.0" in the Nucleus plugin — vtool
            // patches the binary's loader command so macOS 26 enables the
            // Liquid-Glass / large-corner-radius treatment automatically.
            // Older macOS releases ignore the SDK marker.
        }
    }
}
