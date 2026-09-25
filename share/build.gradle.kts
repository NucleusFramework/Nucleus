import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    id("nucleus.native-module")
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

kotlin {
    // AGP's JdkImageTransform cannot jlink against JDK 25 yet (see examples/cmp-demo).
    jvmToolchain(21)

    // Desktop: the Rust JNI bridge. The crate and its libraries keep the JVM-module
    // layout (src/main/native, src/main/resources) shared by every JNI module's CI.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "dev.nucleusframework.share"
        compileSdk = 36
        minSdk = 21
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmMain {
            resources.srcDir("src/main/resources")
            dependencies {
                implementation(project(":core-runtime"))
            }
        }
        androidMain.dependencies {
            implementation(libs.coroutines.android)
        }
    }
}

nucleusNative {
    macos("nucleus_share", "Compiles the Rust share bridge into macOS dylibs (arm64 + x64)")
    linux("nucleus_share", "Compiles the Rust share bridge into the host Linux shared library")
    windows("nucleus_share", "Compiles the Rust share bridge into Windows DLLs (x64 + ARM64)")
}

// The desktop bridge is derived from robius-share (MIT): its notice travels with the
// binaries — see THIRD_PARTY_NOTICES.md §5.
tasks.named<Jar>("jvmJar") {
    metaInf {
        from(rootProject.file("THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("licenses")) {
            into("licenses")
        }
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.share", publishVersion)

    pom {
        name.set("Nucleus Share")
        description.set(
            "Native share sheet for Kotlin Multiplatform: Android Sharesheet, iOS UIActivityViewController, " +
                "macOS NSSharingServicePicker, Windows Share UI and the Linux XDG portal.",
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
