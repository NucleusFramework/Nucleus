import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-agnostic widget runtime: the Glance-like API (NucleusWidget, composables, state,
// actions) and the one-shot composition that turns it into a WidgetElement tree. Hosts
// (widget-windows, widget-macos) render that tree. Android is the exception: its host (an
// AppWidgetProvider drawing RemoteViews) needs no native code and lives in androidMain.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
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
    jvmToolchain(21)

    compilerOptions {
        // The runtime owns the host contract it marks.
        optIn.add("dev.nucleusframework.widget.InternalNucleusWidgetApi")
        optIn.add("dev.nucleusframework.widget.ExperimentalNucleusWidgetApi")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // WidgetKit on iOS (widget-ios): the widgets compose inside the app's widget extension.
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "dev.nucleusframework.widget"
        compileSdk = 37
        // PendingIntent.FLAG_IMMUTABLE; Glance's floor too.
        minSdk = 23
        // The RemoteViews layouts, colors and drawables the host draws widgets with.
        androidResources { enable = true }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            // compileOnly: `NucleusApplicationScope.widgets { }` is only callable by an app that
            // already runs nucleusApplication; never forced on consumers.
            compileOnly(project(":nucleus-application"))
        }
        androidMain.dependencies {
            implementation(libs.coroutines.android)
        }
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.widget-runtime", publishVersion)

    pom {
        name.set("Nucleus Widget Runtime")
        description.set("Glance-like API for widgets (Windows 11 widgets board, macOS WidgetKit, Android app widgets)")
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
