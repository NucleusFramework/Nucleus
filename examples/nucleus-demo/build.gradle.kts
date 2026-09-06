import dev.nucleusframework.desktop.application.dsl.AppImageCategory
import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.ReleaseChannel
import dev.nucleusframework.desktop.application.dsl.ReleaseType
import dev.nucleusframework.desktop.application.dsl.SigningAlgorithm
import dev.nucleusframework.desktop.application.dsl.SnapCompression
import dev.nucleusframework.desktop.application.dsl.SnapConfinement
import dev.nucleusframework.desktop.application.dsl.SnapGrade
import dev.nucleusframework.desktop.application.dsl.SnapPlug
import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)
    implementation(libs.compose.material3)
    implementation(project(":core-runtime"))
    implementation(project(":aot-runtime"))
    implementation(project(":updater-runtime"))
    implementation(project(":native-http"))
    implementation(project(":darkmode-detector"))
    implementation(project(":system-color"))
    implementation(project(":decorated-window-material3"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(project(":energy-manager"))
    implementation(project(":taskbar-progress"))
    implementation(project(":taskbar-progress-tao"))
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
    implementation(libs.reorderable)
    implementation("com.materialkolor:material-kolor:4.1.1")
    implementation(libs.compose.material.icons.extended)
    // Trackpad Lab: an embedded native WebView (WKWebView / WebKitGTK / WebView2)
    // to check trackpad scrolling over a NativeView. The published artifact was
    // built against an older Nucleus; the in-tree modules must win.
    implementation(libs.composewebview) {
        exclude(group = "dev.nucleusframework")
    }
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

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "com.example.demo.generated.resources"
}

val releaseVersion =
    System
        .getenv("RELEASE_VERSION")
        ?.removePrefix("v")
        ?.takeIf { it.isNotBlank() && it.first().isDigit() }
        ?: "1.0.0"

val nativePackageVersion = releaseVersion.substringBefore("-")

nucleus.application {
    mainClass = "com.example.demo.MainKt"

    buildTypes {
        release {
            proguard {
                isEnabled = true
                optimize = true
                obfuscate = true
            }
        }
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "nucleus-sample"
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
        // --- AOT cache (JDK 25+) ---
        // Defaults to a portable cache (metadata only), safe to build in CI and ship to any CPU.
        // Opt into cached adapter code with aotCache { compatibility = AotCacheCompatibility.NATIVE }.
        enableAotCache = System.getenv("GITHUB_REF") != null
//        splashImage = "splash.png" // Splash screen image file
        homepage = "https://github.com/KdroidFilter/NucleusDemo"

        // --- Compression ---
        // Ultra enables DEB xz -9e + DMG LZMA post-processing. AppImage/portable should stay
        // lighter (FUSE/squashfs cold start and portable self-extract) via format overrides below.
        compressionLevel = CompressionLevel.Ultra

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
                owner = "NucleusFramework"
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
            rpmPackageVersion = nativePackageVersion

            // --- Pacman package ---
            pacmanDepends = listOf("gtk3", "libx11")
            pacmanPackageVersion = nativePackageVersion

            // --- AppImage (NEW) ---
            // MimeType is auto-injected from fileAssociation() and protocol() definitions above.
            // No manual desktopEntries override needed for MimeType.
            appImage {
                category = AppImageCategory.Utility
                genericName = "Nucleus Demo"
                synopsis = "Demo app using Nucleus"
                // Override root Ultra: maximum squashfs compression makes AppImage cold starts very slow.
                compressionLevel = CompressionLevel.Normal
            }

            // --- Snap (NEW) ---
            snap {
                // Override the Snap Store name when it differs from packageName (defaults to packageName).
                // name = "nucleus-demo"
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

            // --- GPG signing (deb/rpm) + passwordless self-update ---
            // Keys: LinuxSigningSettings defaults from compose.desktop.linux.signing.*
            //   CI: LINUX_GPG_* secrets → root gradle.properties (release-desktop / test-packaging)
            //   Local: packaging/linux-signing.local.properties (gitignored) — see .example
            // Verify: gpg --import <pkg>.pub.asc && gpg --verify <pkg>.asc <pkg>
            signing {
                enabled.set(true)
                silentUpdate.set(true)
                val localSigning = file("packaging/linux-signing.local.properties")
                if (localSigning.isFile) {
                    val props =
                        localSigning
                            .readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                            .associate { line ->
                                val i = line.indexOf('=')
                                line.substring(0, i).trim() to line.substring(i + 1).trim()
                            }

                    fun local(name: String): String? = props[name]?.takeIf { it.isNotEmpty() }
                    local("compose.desktop.linux.signing.keyId")?.let { keyId.set(it) }
                    local("compose.desktop.linux.signing.keyFile")?.let { keyFile.set(file(it)) }
                    local("compose.desktop.linux.signing.passphrase")?.let { passphrase.set(it) }
                }
            }
        }

        // ========== WINDOWS ==========
        windows {
            packageVersion = nativePackageVersion
            exePackageVersion = nativePackageVersion
            msiPackageVersion = nativePackageVersion

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

            // --- Portable EXE ---
            // Override root Ultra so self-extract stays reasonable while NSIS/DEB keep max packing.
            portable {
                compressionLevel = CompressionLevel.Normal
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
            packageVersion = nativePackageVersion
            packageBuildVersion = nativePackageVersion
            bundleID = "dev.nucleusframework.demo"
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
