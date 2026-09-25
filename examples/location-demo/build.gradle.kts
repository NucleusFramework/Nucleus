import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":location"))
    implementation(libs.coroutines.core)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

nucleus.application {
    mainClass = "dev.nucleusframework.locationdemo.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Nsis, TargetFormat.Dmg, TargetFormat.Deb)
        appName = "Location Demo"
        packageName = "LocationDemo"
        packageVersion = "1.0.0"

        // Core Location never prompts an app without a usage description.
        macOS {
            infoPlist {
                extraKeysRawXml =
                    """
                    <key>NSLocationUsageDescription</key>
                    <string>Location Demo prints where this machine is.</string>
                    <key>NSLocationWhenInUseUsageDescription</key>
                    <string>Location Demo prints where this machine is.</string>
                    """.trimIndent()
            }
        }
    }
}
