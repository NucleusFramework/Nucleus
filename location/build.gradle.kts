import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
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
    // Desktop: the Rust JNI bridge in src/main/native, shipped under src/main/resources (see
    // `nucleus.native-module`, which copies it into this target only).
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Android, iOS and the web talk to the platform directly: no Rust.
    android {
        namespace = "dev.nucleusframework.location"
        compileSdk = 37
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // Web: the browser Geolocation API, shared by both targets in webMain. Tests run on Node,
    // which needs no browser on the build machine.
    js {
        browser { testTask { enabled = false } }
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { enabled = false } }
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(project(":core-runtime"))
        }
    }
}

nucleusNative {
    macos("nucleus_location", "Compiles the Rust JNI bridge into macOS dylibs (arm64 + x64)")
    linux("nucleus_location", "Compiles the Rust JNI bridge into the host Linux shared library")
    windows("nucleus_location", "Compiles the Rust JNI bridge into Windows DLLs (x64 + ARM64)")
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.location", publishVersion)

    pom {
        name.set("Nucleus Location")
        description.set(
            "Kotlin Multiplatform geolocation: Windows Geolocator, macOS Core Location and Linux " +
                "XDG portal / GeoClue through a Rust JNI bridge on desktop, LocationManager on " +
                "Android, Core Location on iOS, the Geolocation API on the web.",
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
