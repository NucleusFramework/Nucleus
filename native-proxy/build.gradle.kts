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
    testImplementation(libs.junit)
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

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge into Windows DLLs (x64 + ARM64)"
    group = "build"
    val nativeDir = file("src/main/native/windows")
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "win32-x64/nucleus_proxy.dll")
    onlyIf { Os.isFamily(Os.FAMILY_WINDOWS) && !checkFile.exists() }
    inputs.dir(nativeDir)
    outputs.dir(outputDir)
    workingDir(nativeDir)
    commandLine("cmd", "/c", File(nativeDir, "build.bat").absolutePath)
}

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the ObjC JNI bridge into macOS dylibs (arm64 + x86_64)"
    group = "build"
    val nativeDir = file("src/main/native/macos")
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "darwin-aarch64/libnucleus_proxy.dylib")
    onlyIf { Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
    inputs.dir(nativeDir)
    outputs.dir(outputDir)
    workingDir(nativeDir)
    commandLine("bash", File(nativeDir, "build.sh").absolutePath)
}

val buildNativeLinux by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge into a Linux shared library"
    group = "build"
    val nativeDir = file("src/main/native/linux")
    val outputDir = file("src/main/resources/nucleus/native")
    val arch = if (System.getProperty("os.arch") == "aarch64") "linux-aarch64" else "linux-x64"
    val checkFile = File(outputDir, "$arch/libnucleus_proxy.so")
    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
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
    coordinates("dev.nucleusframework", "nucleus.native-proxy", publishVersion)

    pom {
        name.set("Nucleus Native Proxy")
        description.set(
            "OS proxy configuration integration (WinHTTP/WPAD/PAC on Windows; " +
                "SCDynamicStore/CFNetwork PAC on macOS; GSettings/KDE/env on Linux) " +
                "for JVM desktop applications",
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
