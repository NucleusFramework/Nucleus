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
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Nucleus Lab: the single test bench app. Assembles the Metro graph from every probe module
// and owns the shell (navigation, checks, timeline, sessions) and the packaging.
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.metro)
    id("dev.nucleusframework")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        optIn.add("dev.nucleusframework.window.ExperimentalNucleusApi")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":examples:lab:designsystem"))
    implementation(project(":examples:lab:probes:window"))
    implementation(project(":examples:lab:probes:workspace"))
    implementation(project(":examples:lab:probes:input"))
    implementation(project(":examples:lab:probes:rendering"))
    implementation(project(":examples:lab:probes:shell"))
    implementation(project(":examples:lab:probes:notifications"))
    implementation(project(":examples:lab:probes:lifecycle"))
    implementation(project(":examples:lab:probes:updater"))
    implementation(project(":examples:lab:probes:system"))
    implementation(project(":examples:lab:probes:fixtures"))
    implementation(project(":darkmode-detector"))
    implementation(libs.coroutines.swing)

    testImplementation(kotlin("test"))
}

// IntelliJ's icon libraries (Jewel) depend on IntelliJ's fork of kotlinx-coroutines, which ships
// the same classes as kotlinx-coroutines itself: two copies on one classpath, and ProGuard refuses
// the duplicates. The Lab runs on the regular library only.
configurations.configureEach {
    exclude(group = "org.jetbrains.intellij.deps.kotlinx")
    // Same story for JNA: Jewel asks for jna-jpms, other libraries for jna — identical classes.
    exclude(group = "net.java.dev.jna", module = "jna-jpms")
}

// Compiled to class file 69: the app has to run on a 25 JVM, resolved through a toolchain.
val jvm25 =
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        .map { it.metadata.installationPath.asFile.absolutePath }

val releaseVersion =
    System
        .getenv("RELEASE_VERSION")
        ?.removePrefix("v")
        ?.takeIf { it.isNotBlank() && it.first().isDigit() }
        ?: "1.0.0"

val nativePackageVersion = releaseVersion.substringBefore("-")

nucleus.application {
    mainClass = "dev.nucleusframework.lab.app.MainKt"
    javaHome = jvm25.get()
    jvmArgs(
        // Fixtures relaunch this JVM and need the main class even where sun.java.command is not set.
        "-Dlab.mainClass=dev.nucleusframework.lab.app.MainKt",
        // JNI and FFM (SwiftUI bridge, zstd, every Nucleus native) without JDK 25's restricted-method warnings.
        "--enable-native-access=ALL-UNNAMED",
    )

    // The Lab dogfoods every runtime optimization; partial redraw also gives the rendering
    // probes and the partial-redraw fixtures the Compose patch they inspect.
    nucleusOptimization = true

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "nucleus-lab"
    }

    additionalLaunchers {
        create("nucleus-lab-cli") {
            mainClass = "dev.nucleusframework.lab.app.LabCliKt"
            winConsole = true
        }
    }

    buildTypes {
        release {
            proguard {
                isEnabled = true
                optimize = true
                obfuscate = true
                consumerRules = true
                configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }
    }

    nativeDistributions {
        // The Lab is the reference app of every packaging pipeline (release-desktop, test-packaging).
        targetFormats(*TargetFormat.entries.toTypedArray())
        appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        appName = "Nucleus Lab"
        packageName = "NucleusLab"
        packageVersion = releaseVersion
        homepage = "https://github.com/NucleusFramework/Nucleus"
        cleanupNativeLibs = true
        // Portable cache (metadata only), safe to build in CI and ship to any CPU.
        enableAotCache = System.getenv("GITHUB_REF") != null
        // Ultra = DEB xz -9e + DMG LZMA; AppImage and portable stay lighter (cold start, self-extract).
        compressionLevel = CompressionLevel.Ultra
        artifactName = $$"${name}-${version}-${os}-${arch}.${ext}"
        // UpdateFeedServer (updater probes) and the JDK HttpClient (network probes).
        modules("jdk.httpserver", "java.net.http")

        // Deep links into any probe: nucleus-lab://probe/<id>?key=value
        protocol("NucleusLab", "nucleus-lab")
        fileAssociation(mimeType = "application/x-nucleus-lab", extension = "nlab", description = "Nucleus Lab report")

        // The release the updater probe reads with its GitHub source.
        publish {
            github {
                enabled = true
                owner = "NucleusFramework"
                repo = "Nucleus"
                channel = ReleaseChannel.Latest
                releaseType = ReleaseType.Release
            }
        }

        linux {
            iconFile.set(project.file("packaging/icons/Icon.png"))
            debMaintainer = "Nucleus <dev@nucleusframework.dev>"
            debDepends = listOf("libfuse2", "libgtk-3-0")
            debPackageVersion = releaseVersion
            rpmRequires = listOf("gtk3", "libX11")
            rpmPackageVersion = nativePackageVersion
            pacmanDepends = listOf("gtk3", "libx11")
            pacmanPackageVersion = nativePackageVersion
            appImage {
                category = AppImageCategory.Development
                genericName = "Nucleus Lab"
                synopsis = "Test bench for every Nucleus runtime module"
                compressionLevel = CompressionLevel.Normal
            }
            snap {
                name = "nucleus-lab"
                confinement = SnapConfinement.Strict
                grade = SnapGrade.Stable
                summary = "Nucleus test bench"
                base = "core22"
                plugs = listOf(SnapPlug.Desktop, SnapPlug.Home, SnapPlug.Network)
                autoStart = false
                compression = SnapCompression.Xz
            }
            flatpak {
                runtime = "org.freedesktop.Platform"
                runtimeVersion = "24.08"
                sdk = "org.freedesktop.Sdk"
                branch = "master"
                finishArgs = listOf("--share=ipc", "--socket=x11", "--socket=wayland")
            }
            // GPG signing (deb/rpm) + passwordless self-update. CI: LINUX_GPG_* secrets → gradle.properties;
            // local: packaging/linux-signing.local.properties (gitignored), see the .example next to it.
            signing {
                enabled.set(true)
                silentUpdate.set(true)
                val localSigning = file("packaging/linux-signing.local.properties")
                if (localSigning.isFile) {
                    val props =
                        localSigning
                            .readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
                            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

                    fun local(name: String): String? = props[name]?.takeIf { it.isNotEmpty() }
                    local("compose.desktop.linux.signing.keyId")?.let { keyId.set(it) }
                    local("compose.desktop.linux.signing.keyFile")?.let { keyFile.set(file(it)) }
                    local("compose.desktop.linux.signing.passphrase")?.let { passphrase.set(it) }
                }
            }
        }

        windows {
            iconFile.set(project.file("packaging/icons/Icon.ico"))
            packageVersion = nativePackageVersion
            exePackageVersion = nativePackageVersion
            msiPackageVersion = nativePackageVersion
            // Inherited from the former nucleus-demo so installs upgrade in place.
            upgradeUuid = "d24e3b8d-3e9b-4cc7-a5d8-5e2d1f0c9f1b"
            signing {
                enabled = true
                // Public self-signed test certificate (release-desktop.yaml uses the same password).
                certificateFile.set(file("packaging/KDroidFilter.pfx"))
                certificatePassword = System.getenv("WIN_CSC_KEY_PASSWORD") ?: "ChangeMe-Temp123!"
                algorithm = SigningAlgorithm.Sha256
                timestampServer = "http://timestamp.digicert.com"
            }
            nsis {
                oneClick = false
                allowElevation = true
                perMachine = true
                allowToChangeInstallationDirectory = true
                createDesktopShortcut = true
                createStartMenuShortcut = true
                runAfterFinish = true
                deleteAppDataOnUninstall = false
                multiLanguageInstaller = true
                installerLanguages = listOf("en_US", "fr_FR")
            }
            portable {
                compressionLevel = CompressionLevel.Normal
            }
            appx {
                // Store identity inherited from the former nucleus-demo (publisher certificate, reservation).
                applicationId = "NucleusDemo"
                publisherDisplayName = "KDroidFilter"
                displayName = "Nucleus Lab"
                addAutoLaunchExtension = true
                publisher = "CN=D541E802-6D30-446A-864E-2E8ABD2DAA5E"
                identityName = "KDroidFilter.NucleusDemo"
                languages = listOf("en-US", "fr-FR")
                backgroundColor = "#001F3F"
                showNameOnTiles = true
                storeLogo.set(project.file("packaging/icons/appx/StoreLogo.png"))
                square44x44Logo.set(project.file("packaging/icons/appx/Square44x44Logo.png"))
                square150x150Logo.set(project.file("packaging/icons/appx/Square150x150Logo.png"))
                wide310x150Logo.set(project.file("packaging/icons/appx/Wide310x150Logo.png"))
            }
        }

        macOS {
            iconFile.set(project.file("packaging/icons/Icon.icns"))
            layeredIconDir.set(layout.projectDirectory.dir("packaging/icons/macos-layered-icon"))
            packageVersion = nativePackageVersion
            packageBuildVersion = nativePackageVersion
            // Inherited from the former nucleus-demo: the provisioning profiles are issued for it.
            bundleID = "dev.nucleusframework.demo"
            appCategory = "public.app-category.developer-tools"
            dockName = "Nucleus Lab"
            dmg {
                title = $$"${productName} ${version}"
                iconSize = 128
            }
            // SMAppService probe: a launch agent that only appends a heartbeat line.
            launchAgents {
                agent("dev.nucleusframework.lab.heartbeat") {
                    arguments("--lab-fixture=lifecycle.agent-heartbeat")
                    startInterval(60)
                }
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    System
        .getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("lab.") || it.startsWith("nucleus.") || it.startsWith("partial.demo.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
}
