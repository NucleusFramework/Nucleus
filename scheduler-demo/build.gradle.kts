import dev.nucleusframework.nucleus.desktop.application.dsl.SigningAlgorithm
import dev.nucleusframework.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinxSerialization)
    id("dev.nucleusframework.nucleus")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":decorated-window-jewel"))
    implementation(project(":decorated-window-jni"))
    implementation(project(":scheduler"))

    val jewelExclusions =
        Action<ExternalModuleDependency> {
            exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
        }
    implementation(libs.jewel.int.ui.standalone, jewelExclusions)
    implementation(libs.intellij.icons)
    implementation(libs.jna.jpms)
    implementation(libs.coroutines.core)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

nucleus.application {
    mainClass = "schedulerdemo.MainKt"
    jvmArgs +=
        listOf(
            "--add-opens",
            "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )

    nativeDistributions {
        packageName = "SchedulerDemo"
        packageVersion = "1.0.0"
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb, TargetFormat.AppX)

        windows {
            signing {
                enabled = true
                certificateFile.set(rootProject.file("example/packaging/NucleusFramework.pfx"))
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
                publisherDisplayName = "NucleusFramework"
                displayName = "Scheduler Demo"
                publisher = "CN=D541E802-6D30-446A-864E-2E8ABD2DAA5E"
                identityName = "NucleusFramework.SchedulerDemo"
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
