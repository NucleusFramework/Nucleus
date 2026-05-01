import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
import io.github.kdroidfilter.nucleus.desktop.application.dsl.SigningAlgorithm
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("io.github.kdroidfilter.nucleus")
}

val isMac =
    org.gradle.internal.os.OperatingSystem
        .current()
        .isMacOsX

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

    // GraalVM font substitutions for native-image
    implementation(project(":graalvm-runtime"))
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
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "jewel-sample"
        march = providers.gradleProperty("nativeMarch").getOrElse("native")
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
        )
        nativeImageConfigBaseDir.set(
            layout.projectDirectory.dir(
                when {
                    org.gradle.internal.os.OperatingSystem
                        .current()
                        .isMacOsX -> "src/main/resources-macos/META-INF/native-image"
                    org.gradle.internal.os.OperatingSystem
                        .current()
                        .isWindows -> "src/main/resources-windows/META-INF/native-image"
                    org.gradle.internal.os.OperatingSystem
                        .current()
                        .isLinux -> "src/main/resources-linux/META-INF/native-image"
                    else -> throw GradleException("Unsupported OS")
                },
            ),
        )
    }

    nativeDistributions {
        compressionLevel = CompressionLevel.Maximum
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)

        packageName = "JewelSample"
        packageVersion = "1.0.0"
        homepage = "https://github.com/kdroidFilter/Nucleus"

        linux {
            debMaintainer = "KDroidFilter <dev@kdroidfilter.com>"
            debDepends = listOf("libfuse2", "libgtk-3-0")
        }

        windows {
            signing {
                enabled = true
                certificateFile.set(rootProject.file("example/packaging/KDroidFilter.pfx"))
                certificatePassword = "ChangeMe-Temp123!"
                algorithm = SigningAlgorithm.Sha256
                timestampServer = "http://timestamp.digicert.com"
            }
        }

        macOS {
            bundleID = "io.github.kdroidfilter.jewelsample"
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
