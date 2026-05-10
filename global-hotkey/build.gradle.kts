import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
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

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C++ JNI bridge into Windows DLLs (x64 + ARM64)"
    group = "build"
    val nativeDir = file("src/main/native/windows")
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "win32-x64/nucleus_global_hotkey.dll")
    onlyIf { Os.isFamily(Os.FAMILY_WINDOWS) && !checkFile.exists() }
    inputs.dir(nativeDir)
    outputs.dir(outputDir)
    workingDir(nativeDir)
    commandLine("cmd", "/c", File(nativeDir, "build.bat").absolutePath)
}

val buildNativeLinux by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge into a Linux shared library"
    group = "build"
    val nativeDir = file("src/main/native/linux")
    val outputDir = file("src/main/resources/nucleus/native")
    val arch = System.getProperty("os.arch").let { if (it == "amd64") "x64" else "aarch64" }
    val checkFile = File(outputDir, "linux-$arch/libnucleus_global_hotkey.so")
    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
    inputs.dir(nativeDir)
    outputs.dir(outputDir)
    workingDir(nativeDir)
    commandLine("bash", File(nativeDir, "build.sh").absolutePath)
}

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the Objective-C JNI bridge into macOS dylibs (arm64 + x86_64)"
    group = "build"
    val nativeDir = file("src/main/native/macos")
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "darwin-aarch64/libnucleus_global_hotkey.dylib")
    onlyIf { Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
    inputs.dir(nativeDir)
    outputs.dir(outputDir)
    workingDir(nativeDir)
    commandLine("bash", File(nativeDir, "build.sh").absolutePath)
}

tasks.processResources {
    dependsOn(buildNativeWindows, buildNativeMacOs, buildNativeLinux)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(buildNativeWindows, buildNativeMacOs, buildNativeLinux)
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.global-hotkey", publishVersion)

    pom {
        name.set("Nucleus Global Hotkey")
        description.set(
            "Cross-platform global hotkey (system-wide keyboard shortcuts) for JVM desktop applications via JNI",
        )
        url.set("https://github.com/kdroidFilter/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("kdroidFilter")
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
