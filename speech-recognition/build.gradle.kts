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

// Desktop only: the Rust bridge over robius-speech. Linux has no native speech
// service, so it gets no library and reports itself unsupported.
nucleusNative {
    macos("nucleus_speech", "Compiles the Rust JNI bridge into macOS dylibs (arm64 + x64)")
    windows("nucleus_speech", "Compiles the Rust JNI bridge into Windows DLLs (x64 + ARM64)")
}

// The JVM and Android artifacts carry robius-speech (compiled in, and ported to Kotlin), so its
// notice travels with them — see THIRD_PARTY_NOTICES.md §5.
val thirdPartyNotices =
    tasks.register<Sync>("thirdPartyNotices") {
        into(layout.buildDirectory.dir("generated/third-party-notices"))
        into("META-INF") {
            from(rootProject.file("THIRD_PARTY_NOTICES.md"))
            from(rootProject.file("licenses")) { into("licenses") }
        }
    }

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "dev.nucleusframework.speech"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain {
            resources.srcDir(thirdPartyNotices)
        }
        jvmMain {
            resources.srcDirs(nucleusNative.jvmResources, thirdPartyNotices)
            dependencies {
                implementation(project(":core-runtime"))
            }
        }
    }
}

// `-Dnucleus.speech.live=true` runs the desktop test that opens the real microphone.
tasks.named<Test>("jvmTest") {
    providers.systemProperty("nucleus.speech.live").orNull?.let { systemProperty("nucleus.speech.live", it) }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.speech-recognition", publishVersion)

    pom {
        name.set("Nucleus Speech Recognition")
        description.set(
            "Native streaming speech-to-text for Kotlin Multiplatform: SAPI and SFSpeechRecognizer on desktop " +
                "(through robius-speech), SpeechRecognizer on Android, SFSpeechRecognizer on iOS.",
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
