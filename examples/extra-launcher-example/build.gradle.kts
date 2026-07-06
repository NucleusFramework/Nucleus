import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    id("io.github.kdroidfilter.nucleus")
}

dependencies {
    implementation(nucleus.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
}

nucleus.application {
    mainClass = "com.example.extralauncher.MainKt"

    additionalLaunchers {
        create("Cli") {
            mainClass = "com.example.extralauncher.CliKt"
            winConsole = true
        }
    }

    nativeDistributions {
        targetFormats(TargetFormat.AppImage, TargetFormat.Exe)
        appName = "Nucleus ExtraLauncher Demo"
        packageName = "NucleusExtraLauncherDemo"
        packageVersion = "1.0.0"
    }
}
