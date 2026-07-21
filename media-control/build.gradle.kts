import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    api(project(":core-runtime"))
    implementation(libs.kotlinx.serialization.json)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val nativeResourceDir = layout.projectDirectory.dir("src/main/resources/nucleus/native")

val buildNativeLinux by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge into a Linux shared library"
    group = "build"
    val hasPrebuilt =
        nativeResourceDir
            .dir("linux-x64")
            .file("libnucleus_media_control_linux.so")
            .asFile
            .exists()
    enabled = Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) && !hasPrebuilt

    val nativeDir = layout.projectDirectory.dir("src/main/native/linux")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

val buildNativeMacos by tasks.registering(Exec::class) {
    description = "Compiles the Objective-C JNI bridge into macOS dylibs (arm64 + x86_64)"
    group = "build"
    val hasPrebuilt =
        nativeResourceDir
            .dir("darwin-aarch64")
            .file("libnucleus_media_control_macos.dylib")
            .asFile
            .exists() &&
            nativeResourceDir
                .dir("darwin-x64")
                .file("libnucleus_media_control_macos.dylib")
                .asFile
                .exists()
    enabled = Os.isFamily(Os.FAMILY_MAC) && !hasPrebuilt

    val nativeDir = layout.projectDirectory.dir("src/main/native/macos")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C++ JNI bridge into Windows DLLs (x64 + arm64)"
    group = "build"
    val hasPrebuilt =
        nativeResourceDir
            .dir("win32-x64")
            .file("nucleus_media_control_windows.dll")
            .asFile
            .exists()
    enabled = Os.isFamily(Os.FAMILY_WINDOWS) && !hasPrebuilt

    val nativeDir = layout.projectDirectory.dir("src/main/native/windows")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("cmd", "/c", nativeDir.file("build.bat").asFile.absolutePath)
}

tasks.processResources {
    dependsOn(buildNativeLinux, buildNativeMacos, buildNativeWindows)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(buildNativeLinux, buildNativeMacos, buildNativeWindows)
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.media-control", publishVersion)

    pom {
        name.set("Nucleus Media Control")
        description.set(
            "OS-level media controls (play/pause, metadata, seek) via MPRIS (Linux), " +
                "MPNowPlayingInfoCenter (macOS), and SMTC (Windows)",
        )
        url.set("https://github.com/NucleusFramework/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("nucleusframework")
                name.set("NucleusFramework")
                url.set("https://github.com/NucleusFramework")
            }
        }

        scm {
            url.set("https://github.com/NucleusFramework/Nucleus")
            connection.set("scm:git:git://github.com/NucleusFramework/Nucleus.git")
            developerConnection.set("scm:git:ssh://git@github.com/NucleusFramework/Nucleus.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
