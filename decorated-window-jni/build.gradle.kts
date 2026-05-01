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
    api(project(":decorated-window-core"))
    api(project(":decorated-window-awt"))
    implementation(project(":core-runtime"))
    implementation(libs.compose.desktop.common)
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

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the Objective-C JNI bridge into macOS dylibs (arm64 + x64)"
    group = "build"
    val nativeDir = file("src/main/native/macos")
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "darwin-aarch64/libnucleus_macos_jni.dylib")
    onlyIf { Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
    inputs.dir(nativeDir)
    outputs.dir(outputDir)
    workingDir(nativeDir)
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge into Windows DLLs (x64 + ARM64)"
    group = "build"
    val nativeDir = file("src/main/native/windows")
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "win32-x64/nucleus_windows_decoration.dll")
    onlyIf { Os.isFamily(Os.FAMILY_WINDOWS) && !checkFile.exists() }
    inputs.dir(nativeDir)
    outputs.dir(outputDir)
    workingDir(nativeDir)
    commandLine("cmd", "/c", File(nativeDir, "build.bat").absolutePath)
}

val buildNativeLinux by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge into Linux shared libraries (x64 + aarch64)"
    group = "build"
    val nativeDir = file("src/main/native/linux")
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "linux-x64/libnucleus_linux_jni.so")
    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
    inputs.dir(nativeDir)
    outputs.dir(outputDir)
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

mavenPublishing {
    coordinates("io.github.kdroidfilter", "nucleus.decorated-window-jni", publishVersion)

    pom {
        name.set("Nucleus Decorated Window JNI")
        description.set("JBR-free custom decorated window with native title bar for Compose Desktop (via JNI)")
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
