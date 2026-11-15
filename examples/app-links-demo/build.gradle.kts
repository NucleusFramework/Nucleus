import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fixture for the app links E2E (docs/internals/app-links.md): a packaged app that logs every
// launch's arguments and every onDeepLink delivery, so a shell can check which https:// links
// Windows hands to it.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
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

nucleus.application {
    mainClass = "applinksdemo.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.AppX, TargetFormat.Nsis)
        appName = "App Links Demo"
        packageName = "AppLinksDemo"
        packageVersion = "1.0.0"
        vendor = "Nucleus"
        description = "App links E2E fixture"

        protocol("AppLinksDemo", "app-links-demo")
        // Sideloaded AppX packages trust their hosts at install, no windows-app-web-link needed.
        appLinks("applinks.nucleus.test", "*.applinks-wild.nucleus.test")

        windows {
            appx {
                identityName = "Nucleus.AppLinksDemo"
                publisher = "CN=Nucleus"
                publisherDisplayName = "Nucleus"
                applicationId = "AppLinksDemo"
            }
        }
    }
}
