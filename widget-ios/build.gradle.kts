// WidgetKit on iOS: the 1:1 app-side API (WidgetCenter, ControlCenter), and the host of the
// Glance-like widgets. Unlike macOS, the widgets compose inside the app's widget extension, which
// links the app's Kotlin framework and calls the C entry points of this module; the Swift side
// (src/swift) is added to the app's targets as sources.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // Widget content is @Composable: the host's tests compose real widgets.
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

kotlin {
    compilerOptions {
        optIn.add("dev.nucleusframework.widget.InternalNucleusWidgetApi")
        optIn.add("dev.nucleusframework.widget.ExperimentalNucleusWidgetApi")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // A Swift class (src/swift/NucleusWidgetCenter.swift), looked up through the ObjC runtime.
        target.compilations
            .getByName("main")
            .cinterops
            .create("objcbridge")
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":widget-runtime"))
            api(libs.coroutines.core)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.widget-ios", publishVersion)

    pom {
        name.set("Nucleus Widget iOS")
        description.set("WidgetKit API (WidgetCenter, ControlCenter) and host of the Glance-like widgets on iOS")
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
