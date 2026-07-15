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

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    implementation(project(":system-color"))
    implementation(project(":system-info"))
    implementation(project(":decorated-window-jewel"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))

    val jewelExclusions =
        Action<ExternalModuleDependency> {
            exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
        }
    implementation(libs.jewel.int.ui.standalone, jewelExclusions)
    implementation(libs.intellij.icons)
    implementation(libs.jna.jpms)

    implementation(libs.coroutines.core)

    // Lets-Plot charting
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin-kernel:4.15.0")
    implementation("org.jetbrains.lets-plot:lets-plot-common:4.11.0")
    implementation("org.jetbrains.lets-plot:canvas:4.11.0")
    implementation("org.jetbrains.lets-plot:plot-raster:4.11.0")
    implementation("org.jetbrains.lets-plot:lets-plot-compose-desktop:3.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("org.slf4j:slf4j-simple:2.0.18")
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
    mainClass = "systeminfodemo.MainKt"
    jvmArgs +=
        listOf(
            "--add-opens",
            "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.ORACLE
        imageName = "system-info-demo"
        optimization = NativeImageOptimization.SIZE
    }

    nativeDistributions {
        compressionLevel = CompressionLevel.Maximum
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)

        packageName = "SystemInfo"
        packageVersion = "1.0.0"
        homepage = "https://github.com/NucleusFramework/Nucleus"

        linux {
            debMaintainer = "KDroidFilter <dev@kdroidfilter.com>"
        }

        windows {
            signing {
                enabled = true
                certificateFile.set(rootProject.file("examples/nucleus-demo/packaging/KDroidFilter.pfx"))
                certificatePassword = "ChangeMe-Temp123!"
                algorithm = SigningAlgorithm.Sha256
                timestampServer = "http://timestamp.digicert.com"
            }
        }

        macOS {
            bundleID = "dev.nucleusframework.systeminfo"
            dockName = "SystemInfo"
        }
    }
}

tasks.withType<Jar> {
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.EC")
}
