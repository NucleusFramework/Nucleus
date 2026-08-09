import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("nucleus.native-module")
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

nucleusNative {
    macos("nucleus_macos_jni")
    windows("nucleus_windows_decoration")
    linux("nucleus_linux_jni")
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.decorated-window-jni", publishVersion)

    pom {
        name.set("Nucleus Decorated Window JNI")
        description.set("JBR-free custom decorated window with native title bar for Compose Desktop (via JNI)")
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
