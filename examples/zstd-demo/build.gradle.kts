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
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Nucleus windowing: Tao backend + Material 3 decorated chrome.
    implementation(project(":decorated-window-tao"))
    implementation(project(":decorated-window-material3"))
    implementation(project(":decorated-window-core"))
    implementation(project(":nucleus-application"))
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))

    // OS-native file / directory pickers.
    implementation(libs.filekit.dialogs)

    // Extract-and-load native lib (issue #317): zstd-kmp ships libzstd-kmp.{dylib,so,dll} inside
    // its JAR and loads a temp-extracted copy via System.load(temp). Pairing it with the Pkg store
    // format below activates the sandboxed pipeline (marker + bytecode rewrite + runtime shim), so
    // the whole redirect works in a signed store bundle with no upstream library changes.
    implementation(libs.zstd.kmp.jvm)
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
    mainClass = "dev.nucleusframework.zstddemo.MainKt"

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "zstd-demo"
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
        // zstd-kmp's JNI classes/fields and its `jni/<arch>/libzstd-kmp.*` resource are only
        // reachable through native code, so they'd be stripped by the closed-world analysis.
        // App-specific metadata is shipped in
        // src/main/resources/META-INF/native-image/.../reachability-metadata.json (auto-discovered
        // on the classpath). If zstd-kmp changes, refresh it with `runWithNativeAgent`.
    }

    nativeDistributions {
        // Pkg activates the sandboxed pipeline on macOS (the point of this demo);
        // Dmg / Nsis keep the plain desktop builds working too.
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Pkg)
        compressionLevel = CompressionLevel.Maximum
        appName = "Zstd Demo"
        packageName = "ZstdDemo"
        packageVersion = "1.0.0"

        macOS {
            bundleID = "dev.nucleusframework.zstddemo"
        }
    }
}
