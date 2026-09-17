import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    kotlin("jvm")
    id("nucleus.native-module")
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
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
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

val nativeOutputDir = layout.projectDirectory.dir("src/main/resources/nucleus/native").asFile

fun hostArchDir(prefix: String): String {
    val arch = System.getProperty("os.arch").lowercase()
    val suffix = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x64"
    return "$prefix-$suffix"
}

val nativeTasks =
    with(nucleusNative) {
        listOf(
            macos("nucleus_fs_watcher", "Compiles the Rust JNI bridge into macOS dylibs (arm64 + x64)"),
            linux("nucleus_fs_watcher", "Compiles the Rust JNI bridge into the host Linux shared library"),
            windows("nucleus_fs_watcher", "Compiles the Rust JNI bridge into Windows DLLs (x64 + ARM64)"),
        )
    }

val verifyNativeResourcePresence by tasks.registering {
    description = "Verifies the current host native artifact expected from the local build script exists in resources"
    group = "verification"
    dependsOn(nativeTasks)
    val expectedArtifactPath =
        when {
            Os.isFamily(Os.FAMILY_MAC) ->
                File(nativeOutputDir, "${hostArchDir("darwin")}/libnucleus_fs_watcher.dylib").absolutePath
            Os.isFamily(Os.FAMILY_WINDOWS) ->
                File(nativeOutputDir, "${hostArchDir("win32")}/nucleus_fs_watcher.dll").absolutePath
            else ->
                File(nativeOutputDir, "${hostArchDir("linux")}/libnucleus_fs_watcher.so").absolutePath
        }

    doLast {
        val expectedArtifact = File(expectedArtifactPath)
        if (!expectedArtifact.exists()) {
            throw GradleException("Expected native artifact is missing: $expectedArtifact")
        }
    }
}

tasks.processResources {
    dependsOn(verifyNativeResourcePresence)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(verifyNativeResourcePresence)
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.fs-watcher", publishVersion)

    pom {
        name.set("Nucleus FS Watcher")
        description.set("Filesystem watching API for JVM desktop applications, backed by a native watcher bridge.")
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
