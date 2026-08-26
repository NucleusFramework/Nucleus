import dev.nucleusframework.desktop.application.dsl.SigningAlgorithm
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinxSerialization)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":decorated-window-jewel"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(project(":scheduler"))

    val jewelExclusions =
        Action<ExternalModuleDependency> {
            exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
        }
    implementation(libs.jewel.int.ui.standalone, jewelExclusions)
    implementation(libs.intellij.icons)
    // Jewel 0.39+ IntUiTheme needs IconManager/DefaultIconManager from these modules
    implementation(libs.intellij.icons.api)
    implementation(libs.intellij.icons.impl)
    implementation(libs.jna.jpms)
    implementation(libs.coroutines.core)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

nucleus.application {
    mainClass = "schedulerdemo.MainKt"
    nativeDistributions {
        packageName = "SchedulerDemo"
        packageVersion = "1.0.0"
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb, TargetFormat.AppX)

        windows {
            signing {
                enabled = true
                certificateFile.set(rootProject.file("examples/nucleus-demo/packaging/KDroidFilter.pfx"))
                certificatePassword = "ChangeMe-Temp123!"
                algorithm = SigningAlgorithm.Sha256
                timestampServer = "http://timestamp.digicert.com"
            }

            nsis {
                perMachine = false
                createDesktopShortcut = true
                createStartMenuShortcut = true
                runAfterFinish = true
            }

            appx {
                applicationId = "SchedulerDemo"
                publisherDisplayName = "KDroidFilter"
                displayName = "Scheduler Demo"
                publisher = "CN=D541E802-6D30-446A-864E-2E8ABD2DAA5E"
                identityName = "KDroidFilter.SchedulerDemo"
                languages = listOf("en-US", "fr-FR")
            }
        }
    }
}

tasks.withType<Jar> {
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.EC")
}
