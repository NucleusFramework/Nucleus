import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fixture for the app links E2E (docs/internals/app-links.md): a packaged app that logs every
// launch's arguments and every onDeepLink delivery, so a shell can check which https:// links
// the OS hands to it.
//
// macOS Universal Links need a Developer ID build and a profile granting Associated Domains, kept
// out of the repo: -PappLinksDemo.macIdentity="Name (TEAMID)" -PappLinksDemo.macProfile=<file>
// [-PappLinksDemo.macKeychain=<keychain>] [-PappLinksDemo.hosts=host1,host2].

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
        targetFormats(TargetFormat.AppX, TargetFormat.Nsis, TargetFormat.Dmg)
        appName = "App Links Demo"
        packageName = "AppLinksDemo"
        packageVersion = "1.0.0"
        vendor = "Nucleus"
        description = "App links E2E fixture"

        protocol("AppLinksDemo", "app-links-demo")
        // Sideloaded AppX packages trust their hosts at install, no windows-app-web-link needed.
        appLinks("applinks.nucleus.test", "*.applinks-wild.nucleus.test")
        providers.gradleProperty("appLinksDemo.hosts").orNull?.let { appLinks(*it.split(',').toTypedArray()) }

        macOS {
            bundleID = "dev.nucleusframework.applinksdemo"
            providers.gradleProperty("appLinksDemo.macProfile").orNull?.let { provisioningProfile.set(file(it)) }
            providers.gradleProperty("appLinksDemo.macIdentity").orNull?.let { macIdentity ->
                signing {
                    sign.set(true)
                    identity.set(macIdentity)
                    providers.gradleProperty("appLinksDemo.macKeychain").orNull?.let { keychain.set(it) }
                }
            }
        }

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
