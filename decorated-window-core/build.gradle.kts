import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    implementation(project(":core-runtime"))
    api(libs.compose.foundation)
}

// ---------- Native JNI builds ----------

val nativeResourceDir = layout.projectDirectory.dir("src/main/resources/nucleus/native")

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the Objective-C JNI bridge for layout direction detection (macOS)"
    group = "build"
    val prebuiltFile = nativeResourceDir.dir("darwin-aarch64").file("libnucleus_layout_direction.dylib").asFile
    enabled = Os.isFamily(Os.FAMILY_MAC) && !prebuiltFile.exists()

    val nativeDir = layout.projectDirectory.dir("src/main/native/macos")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge for layout direction detection (Windows)"
    group = "build"
    val prebuiltFile = nativeResourceDir.dir("win32-x64").file("nucleus_layout_direction.dll").asFile
    enabled = Os.isFamily(Os.FAMILY_WINDOWS) && !prebuiltFile.exists()

    val nativeDir = layout.projectDirectory.dir("src/main/native/windows")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("cmd", "/c", nativeDir.file("build.bat").asFile.absolutePath)
}

val buildNativeLinux by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge for layout direction detection (Linux)"
    group = "build"
    val prebuiltFile = nativeResourceDir.dir("linux-x64").file("libnucleus_layout_direction.so").asFile
    enabled = Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) && !prebuiltFile.exists()

    val nativeDir = layout.projectDirectory.dir("src/main/native/linux")
    inputs.dir(nativeDir)
    outputs.dir(nativeResourceDir)
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

tasks.processResources {
    dependsOn(buildNativeMacOs, buildNativeWindows, buildNativeLinux)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(buildNativeMacOs, buildNativeWindows, buildNativeLinux)
    }
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

mavenPublishing {
    coordinates("io.github.kdroidfilter", "nucleus.decorated-window-core", publishVersion)

    pom {
        name.set("Nucleus Decorated Window Core")
        description.set("Shared types, layout, styling, and resources for Nucleus Decorated Window")
        url.set("https://github.com/kdroidFilter/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("kdroidfilter")
                name.set("kdroidFilter")
                url.set("https://github.com/kdroidFilter")
            }
        }

        scm {
            url.set("https://github.com/kdroidFilter/Nucleus")
            connection.set("scm:git:git://github.com/kdroidFilter/Nucleus.git")
            developerConnection.set("scm:git:ssh://git@github.com/kdroidFilter/Nucleus.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
