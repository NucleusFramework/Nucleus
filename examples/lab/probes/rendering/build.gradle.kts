import org.gradle.internal.os.OperatingSystem

// Rendering probes: Compose conformance on Tao, showcase scene, partial redraw, native views
// (WebView, SwiftUI), GPU textures and platform video.
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.metro)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        optIn.addAll(
            "dev.nucleusframework.window.ExperimentalNucleusApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(project(":examples:lab:designsystem"))
    // Specimens only: the conformance samples are Material 3 / Expressive components and icons.
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.filekit.dialogs)
    // The published artifact was built against an older Nucleus; the in-tree modules must win.
    implementation(libs.composewebview) {
        exclude(group = "dev.nucleusframework")
    }

    testImplementation(kotlin("test"))
}

// The probe's helper libraries are sample code outside CI, like the demos they come from:
// each builds on its own OS only and lands in src/main/resources/nucleus/native (gitignored).
// Run `./gradlew :examples:lab:probes:rendering:buildRenderingNatives` once; the probes say
// what is missing otherwise.
val nativeDir = layout.projectDirectory.dir("src/main/native")
val buildRenderingNatives =
    tasks.register("buildRenderingNatives") {
        group = "build"
        description = "Builds the rendering probes' helper libraries for the host OS (video, SwiftUI)."
    }

fun registerNativeScript(
    name: String,
    script: String,
) {
    val task =
        tasks.register<Exec>(name) {
            workingDir = nativeDir.asFile
            val os = OperatingSystem.current()
            if (os.isWindows) commandLine("cmd", "/c", script) else commandLine("bash", script)
        }
    buildRenderingNatives.configure { dependsOn(task) }
}

when {
    OperatingSystem.current().isMacOsX -> {
        registerNativeScript("buildAvfVideoNative", "macos/build-avf-video.sh")
        registerNativeScript("buildSwiftUiNative", "macos/build-swiftui.sh")
    }
    OperatingSystem.current().isLinux -> registerNativeScript("buildGstVideoNative", "linux/build-gst-video.sh")
    OperatingSystem.current().isWindows -> registerNativeScript("buildMfVideoNative", "windows\\build-mf-video.bat")
}
