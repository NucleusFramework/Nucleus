import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    // Widget content is @Composable: the host's tests compose real widgets.
    alias(libs.plugins.kotlinComposePlugin)
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
    // The Windows host of the Glance-like API (WindowsWidgetHost, found through ServiceLoader).
    api(project(":widget-runtime"))
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("dev.nucleusframework.widget.InternalNucleusWidgetApi")
        optIn.add("dev.nucleusframework.widget.ExperimentalNucleusWidgetApi")
    }
}

nucleusNative {
    windows("nucleus_widget_windows")
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.widget-windows", publishVersion)

    pom {
        name.set("Nucleus Widget Windows")
        description.set("Windows 11 widget provider API (Windows App SDK) for JVM desktop applications via JNI")
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
