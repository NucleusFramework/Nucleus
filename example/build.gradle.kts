import io.github.kdroidfilter.nucleus.desktop.application.dsl.AppImageCategory
import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
import io.github.kdroidfilter.nucleus.desktop.application.dsl.ReleaseChannel
import io.github.kdroidfilter.nucleus.desktop.application.dsl.ReleaseType
import io.github.kdroidfilter.nucleus.desktop.application.dsl.SigningAlgorithm
import io.github.kdroidfilter.nucleus.desktop.application.dsl.SnapCompression
import io.github.kdroidfilter.nucleus.desktop.application.dsl.SnapConfinement
import io.github.kdroidfilter.nucleus.desktop.application.dsl.SnapGrade
import io.github.kdroidfilter.nucleus.desktop.application.dsl.SnapPlug
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("io.github.kdroidfilter.nucleus")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation(project(":core-runtime"))
    implementation(project(":aot-runtime"))
    implementation(project(":updater-runtime"))
    implementation(project(":native-http"))
    implementation(project(":darkmode-detector"))
    implementation(project(":system-color"))
    implementation(project(":decorated-window-material3"))
    implementation(project(":decorated-window-jni"))
    implementation(project(":energy-manager"))
    implementation(project(":taskbar-progress"))
    implementation(project(":notification-common"))
    implementation(project(":notification-macos"))
    implementation(project(":notification-linux"))
    implementation(project(":notification-windows"))
    implementation(project(":autolaunch"))
    implementation(project(":service-management-macos"))
    implementation(project(":launcher-windows"))
    implementation(project(":launcher-linux"))
    implementation(project(":launcher-macos"))
    implementation(project(":global-hotkey"))
    implementation(project(":menu-macos"))
    implementation(project(":sf-symbols"))
    implementation(project(":media-control"))
    implementation(libs.coroutines.swing)
    implementation(project(":graalvm-runtime"))
    implementation(libs.reorderable)
    implementation("com.materialkolor:material-kolor:4.1.1")
    implementation(libs.compose.material.icons.extended)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val releaseVersion =
    System
        .getenv("RELEASE_VERSION")
        ?.removePrefix("v")
        ?.takeIf { it.isNotBlank() }
        ?: "1.0.0"

nucleus.application {
    mainClass = "com.example.demo.MainKt"

    buildTypes {
        release {
            proguard {
                version = "7.8.1"
                isEnabled = true
                optimize = false
            }
        }
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "nucleus-sample"
        march = providers.gradleProperty("nativeMarch").getOrElse("compatibility")
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
        targetFormats(*TargetFormat.entries.toTypedArray())
        appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        appName = "Nucleus Demo"
        packageName = "NucleusDemo"
        packageVersion = releaseVersion

        // ============================================================
        // Nucleus options
        // ============================================================

        // --- Trusted CA certificates ---
        // Certificates are imported into the bundled JVM's cacerts keystore at build time.
//        trustedCertificates.from(files("resources/common/netfree-ca.crt"))

        // --- Native libs handling ---
        cleanupNativeLibs = true // Auto cleanup native libraries
        enableAotCache = System.getenv("GITHUB_REF") != null
//        splashImage = "splash.png" // Splash screen image file
        homepage = "https://github.com/KdroidFilter/NucleusDemo"

        // --- Compression ---
        compressionLevel = CompressionLevel.Maximum

        // --- Artifact naming ---
        // Variables: ${name}, ${version}, ${os}, ${arch}, ${ext}
        artifactName = $$"${name}-${version}-${os}-${arch}.${ext}"

        // --- Deep links protocol ---
        // Registers custom protocol handler (e.g., nucleus://open)
        // Works on all platforms: macOS, Windows (NSIS/MSI), and Linux (via MimeType in .desktop)
        protocol("NucleusDemo", "nucleus")

        // --- File associations ---
        // Works on all platforms: macOS (DMG/PKG), Windows (NSIS/MSI), and Linux (via MimeType in .desktop)
        fileAssociation(
            mimeType = "application/x-nucleus",
            extension = "cdk",
            description = "Nucleus Document",
        )

        // --- Publish to GitHub/S3 ---
        publish {
            github {
                enabled = true
                owner = "kdroidfilter"
                repo = "Nucleus"
                channel = ReleaseChannel.Latest
                releaseType = ReleaseType.Release
            }
            // s3 { ... }
        }

        // ========== ICONS ==========
        linux {
            iconFile.set(project.file("packaging/icons/Icon.png"))
        }
        windows {
            iconFile.set(project.file("packaging/icons/Icon.ico"))
        }
        macOS {
            iconFile.set(project.file("packaging/icons/Icon.icns"))
        }

        // ========== LINUX ==========
        linux {
            // --- DEB package ---
            debMaintainer = "KDroidFilter <dev@kdroidfilter.com>"
            debDepends = listOf("libfuse2", "libgtk-3-0")
            debPackageVersion = releaseVersion

            // --- RPM package ---
            rpmRequires = listOf("gtk3", "libX11")
            rpmPackageVersion = releaseVersion

            // --- AppImage (NEW) ---
            // MimeType is auto-injected from fileAssociation() and protocol() definitions above.
            // No manual desktopEntries override needed for MimeType.
            appImage {
                category = AppImageCategory.Utility
                genericName = "Nucleus Demo"
                synopsis = "Demo app using Nucleus"
            }

            // --- Snap (NEW) ---
            snap {
                confinement = SnapConfinement.Strict
                grade = SnapGrade.Stable
                summary = "Nucleus demo"
                base = "core22"
                plugs = listOf(SnapPlug.Desktop, SnapPlug.Home, SnapPlug.Network)
                autoStart = false
                compression = SnapCompression.Xz
            }

            // --- Flatpak (NEW) ---
            flatpak {
                runtime = "org.freedesktop.Platform"
                runtimeVersion = "24.08" // or "24.08", etc.
                sdk = "org.freedesktop.Sdk"
                branch = "master"
                // Finish args: "--share=ipc", "--socket=x11", "--socket=wayland", "--socket=pulseaudio", "--device=dri", "--filesystem=home"
                finishArgs = listOf("--share=ipc", "--socket=x11", "--socket=wayland")
            }
        }

        // ========== WINDOWS ==========
        windows {
            // --- Upgrade UUID ---
            // Used for Windows updates (auto-generated if null)
            upgradeUuid = "d24e3b8d-3e9b-4cc7-a5d8-5e2d1f0c9f1b"

            // --- Code signing (NEW) ---
            signing {
                enabled = true
                certificateFile.set(file("packaging/KDroidFilter.pfx"))
                certificatePassword = "ChangeMe-Temp123!"
                algorithm = SigningAlgorithm.Sha256
                // Timestamp servers: "http://timestamp.digicert.com", "http://timestamp.sectigo.com", "http://timestamp.globalsign.com"
                timestampServer = "http://timestamp.digicert.com"
            }

            // --- NSIS Installer (NEW) ---
            nsis {
                oneClick = false // Default: true
                allowElevation = true // Default: false
                perMachine = true // Default: false (current user)
                allowToChangeInstallationDirectory = true // Default: false
                createDesktopShortcut = true
                createStartMenuShortcut = true
                runAfterFinish = true
                deleteAppDataOnUninstall = false // Default: false
                multiLanguageInstaller = true // Default: false
                // Languages: "en_US", "fr_FR", "de_DE", "es_ES", "ja_JP", "zh_CN", etc.
                installerLanguages = listOf("en_US", "fr_FR")
            }

            // --- AppX/Windows Store (NEW) ---
            appx {
                applicationId = "NucleusDemo"
                publisherDisplayName = "KDroidFilter"
                displayName = "Nucleus Demo"
                // Auto-inject <desktop4:Extension Category="windows.startupTask"> in Package.appxmanifest.
                // TaskId is auto-resolved to "${applicationId}StartupId" and exposed via NucleusApp.startupTaskId,
                // which AutoLaunch picks up automatically on MSIX builds.
                addAutoLaunchExtension = true
                // Publisher: "CN=..."
                publisher = "CN=D541E802-6D30-446A-864E-2E8ABD2DAA5E"
                identityName = "KDroidFilter.NucleusDemo"
                // Languages: "en-US", "fr-FR", "de-DE", etc.
                languages = listOf("en-US", "fr-FR")
                backgroundColor = "#001F3F"
                showNameOnTiles = true

                // AppX tile logos
                storeLogo.set(project.file("packaging/icons/appx/StoreLogo.png"))
                square44x44Logo.set(project.file("packaging/icons/appx/Square44x44Logo.png"))
                square150x150Logo.set(project.file("packaging/icons/appx/Square150x150Logo.png"))
                wide310x150Logo.set(project.file("packaging/icons/appx/Wide310x150Logo.png"))
            }
        }

        // ========== MACOS ==========
        macOS {
            bundleID = "io.github.kdroidfilter.nucleus.demo"
            appCategory = "public.app-category.utilities"
            dockName = "NucleusDemo"

            // --- Layered Icons (NEW - macOS 26+) ---
            val layeredIcons = layout.projectDirectory.dir("packaging/icons/macos-layered-icon")
            if (layeredIcons.asFile.exists()) {
                layeredIconDir.set(layeredIcons)
            }

            // --- DMG customization ---
            dmg {
                title = $$"${productName} ${version}"
                iconSize = 128
            }
        }
    }
}
