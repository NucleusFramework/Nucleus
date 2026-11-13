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
    // The macOS host of the Glance-like API (MacWidgetHost, found through ServiceLoader).
    api(project(":widget-runtime"))
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("dev.nucleusframework.widget.InternalNucleusWidgetApi")
    }
}

nucleusNative {
    macos("nucleus_widget_macos")
}

// The extension is built with the SwiftUI renderer widget-runtime shares with iOS: editing it rebuilds.
tasks.named("buildNativeMacOs") {
    inputs
        .dir(rootProject.layout.projectDirectory.dir("widget-runtime/src/main/native/swiftui"))
        .withPropertyName("sharedRenderer")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.widget-macos", publishVersion)

    pom {
        name.set("Nucleus Widget macOS")
        description.set(
            "macOS WidgetKit API (WidgetCenter, ControlCenter) and desktop widgets host for JVM desktop applications via JNI",
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
