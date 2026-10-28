import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("nucleus.native-module")
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
    implementation(project(":core-runtime"))
    api(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
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

// Windows only for now: macOS (StoreKit 2) lands separately, Linux has no OS store. Both answer
// "unsupported" from Kotlin, with no library.
nucleusNative {
    windows("nucleus_iap", "Compiles the Windows.Services.Store bridge (Rust) into Windows DLLs (x64 + ARM64)")
}

// MIT notice clause: the native backends derive from tauri-plugin-iap (THIRD_PARTY_NOTICES.md §5).
tasks.named<Jar>("jar") {
    metaInf {
        from(rootProject.file("THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("licenses")) {
            into("licenses")
        }
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.in-app-purchase", publishVersion)

    pom {
        name.set("Nucleus In-App Purchase")
        description.set(
            "In-app purchases for JVM desktop apps on the Microsoft Store (Windows.Services.Store).",
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
