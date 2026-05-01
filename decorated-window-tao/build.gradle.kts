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
    implementation(project(":core-runtime"))
    implementation(libs.compose.desktop.common)
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

// ── Native build ────────────────────────────────────────────────────────────
// Tao + jni crate + per-platform helpers (Metal on macOS, WGL + WndProc deco
// on Windows). Native binaries ship in src/main/resources/nucleus/native/.

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the Rust JNI bridge into a macOS dylib (arm64 + x86_64)"
    group = "build"
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "darwin-aarch64/libnucleus_tao.dylib")
    onlyIf { Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
    inputs.dir(file("src/main/native/src"))
    inputs.file(file("src/main/native/Cargo.toml"))
    outputs.dir(outputDir)
    workingDir(file("src/main/native/macos"))
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the Rust JNI bridge + WGL/Deco helpers into Windows DLLs"
    group = "build"
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "win32-x64/nucleus_tao.dll")
    onlyIf { Os.isFamily(Os.FAMILY_WINDOWS) && !checkFile.exists() }
    inputs.dir(file("src/main/native/src"))
    inputs.file(file("src/main/native/Cargo.toml"))
    inputs.file(file("src/main/native/windows/nucleus_tao_windows_deco.c"))
    inputs.file(file("src/main/native/windows/nucleus_tao_gl.c"))
    outputs.dir(outputDir)
    workingDir(file("src/main/native/windows"))
    commandLine("cmd", "/c", ".\\build.bat")
}

tasks.processResources {
    dependsOn(buildNativeMacOs)
    dependsOn(buildNativeWindows)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(buildNativeMacOs)
        dependsOn(buildNativeWindows)
    }
}

// ── Maven publication ──────────────────────────────────────────────────────

mavenPublishing {
    coordinates("io.github.kdroidfilter", "nucleus.decorated-window-tao", publishVersion)

    pom {
        name.set("Nucleus Decorated Window Tao")
        description.set(
            "Experimental no-AWT decorated window backend for Compose Desktop, " +
                "powered by Tao via direct JNI. macOS only, GraalVM native-image first.",
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
