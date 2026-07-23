import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    id("dev.nucleusframework")
}

dependencies {
    implementation(nucleus.desktop.currentOs)
    implementation(libs.compose.material3)
}

val macAppName = "NetworkExtensionDemo"
val isMac = System.getProperty("os.name").startsWith("Mac")
val extensionDir = layout.projectDirectory.dir("packaging/extension")
val appexOutputDir = layout.buildDirectory.dir("appex")

// Compile the Network Extension .appex (Nucleus does not build .appex itself).
// Nucleus signs it via the appExtensions {} DSL below.
val buildAppex by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Compile the Network Extension .appex."
    onlyIf { isMac }
    inputs.dir(extensionDir)
    outputs.dir(appexOutputDir)
    commandLine(
        "bash",
        extensionDir.file("build.sh").asFile.absolutePath,
        appexOutputDir.get().asFile.absolutePath,
    )
}

nucleus.application {
    mainClass = "dev.nucleusframework.appexdemo.MainKt"

    // The .appex is embedded & signed on the GraalVM native path too (ad-hoc).
    graalvm {
        isEnabled = true
        imageName = "network-extension-demo"
    }

    nativeDistributions {
        targetFormats(TargetFormat.Dmg)
        appName = "Network Extension Demo"
        packageName = macAppName
        packageVersion = "1.0.0"

        macOS {
            bundleID = "dev.nucleusframework.appexdemo"
            appCategory = "public.app-category.utilities"
            entitlementsFile.set(layout.projectDirectory.file("packaging/app.entitlements"))

            // First-class embedding: Nucleus copies the .appex into Contents/PlugIns,
            // signs it with its OWN entitlements, then seals the app without --deep.
            appExtensions {
                extension("NetworkFilter") {
                    appex(appexOutputDir.get().file("NetworkFilter.appex").asFile)
                    entitlements(extensionDir.file("NetworkExtension.entitlements").asFile)
                    // provisioningProfile(file("packaging/NetworkFilter.provisionprofile")) // real distribution
                }
            }

            // For a real, notarizable / App Store build, enable signing so the DMG re-seal
            // keeps the nested extension signature:
            // signing {
            //     sign.set(true)
            //     identity.set("Developer ID Application: You (TEAMID)")
            // }
        }
    }
}

// The .appex must exist before the app image is assembled (JVM and GraalVM paths).
val appImageTasks =
    setOf(
        "createDistributable",
        "createReleaseDistributable",
        "embedGraalvmAppExtensions",
        "embedReleaseGraalvmAppExtensions",
    )
tasks.matching { it.name in appImageTasks }.configureEach { dependsOn(buildAppex) }

