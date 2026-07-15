import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization
import dev.nucleusframework.desktop.application.dsl.SigningAlgorithm
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

val isMac =
    org.gradle.internal.os.OperatingSystem
        .current()
        .isMacOsX

val releaseVersion =
    System
        .getenv("RELEASE_VERSION")
        ?.removePrefix("v")
        ?.takeIf { it.isNotBlank() && it.first().isDigit() }
        ?: "1.0.0"

val nativePackageVersion = releaseVersion.substringBefore("-")

sourceSets {
    main {
        resources.srcDir(
            when {
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isMacOsX -> "src/main/resources-macos"
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isWindows -> "src/main/resources-windows"
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isLinux -> "src/main/resources-linux"
                else -> throw GradleException("Unsupported OS")
            },
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":decorated-window-jewel"))
    implementation(project(":nucleus-application"))
    val jewelExclusions =
        Action<ExternalModuleDependency> {
            exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
        }
    implementation(libs.jewel.int.ui.standalone, jewelExclusions)
    implementation(libs.jewel.markdown.int.ui.standalone.styling, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.autolink, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.gfm.alerts, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.gfm.tables, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.gfm.strikethrough, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.images, jewelExclusions)
    implementation(libs.coil.compose)
    implementation(libs.intellij.icons)

    // Jewel's StandalonePlatformCursorController uses JNA at runtime
    implementation(libs.jna.jpms)
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
    mainClass = "jewelsample.MainKt"
    jvmArgs +=
        listOf(
            "--add-opens",
            "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )

    buildTypes {
        release {
            proguard {
                version = "7.8.1"
                isEnabled = true
                optimize = false
                configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.ORACLE
        imageName = "jewel-sample"
        optimization = NativeImageOptimization.SIZE

    }

    nativeDistributions {
        compressionLevel = CompressionLevel.Maximum
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)

        packageName = "JewelSample"
        packageVersion = releaseVersion
        homepage = "https://github.com/NucleusFramework/Nucleus"

        linux {
            debMaintainer = "KDroidFilter <dev@kdroidfilter.com>"
            debDepends = listOf("libfuse2", "libgtk-3-0")
        }

        windows {
            packageVersion = nativePackageVersion
            exePackageVersion = nativePackageVersion

            signing {
                enabled = true
                certificateFile.set(rootProject.file("examples/nucleus-demo/packaging/KDroidFilter.pfx"))
                certificatePassword = "ChangeMe-Temp123!"
                algorithm = SigningAlgorithm.Sha256
                timestampServer = "http://timestamp.digicert.com"
            }
        }

        macOS {
            packageVersion = nativePackageVersion
            packageBuildVersion = nativePackageVersion
            bundleID = "dev.nucleusframework.jewelsample"
            dockName = "JewelSample"
        }
    }
}

tasks.withType<Jar> {
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.EC")
}
