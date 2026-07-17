import dev.nucleusframework.desktop.application.dsl.NativeImageMarch
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":fs-watcher"))
    implementation(project(":core-runtime"))
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
    mainClass = "dev.nucleusframework.fswatchersmoke.MainKt"

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "fs-watcher-native-smoke"
        // Leave unset for the per-platform default; -PnativeMarch=native overrides it locally.
        providers.gradleProperty("nativeMarch").orNull?.let {
            march = NativeImageMarch.valueOf(it.uppercase())
        }
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=true",
            "-Os",
            "-H:-IncludeMethodData",
        )
    }

    nativeDistributions {
        targetFormats(TargetFormat.Nsis)
        appName = "FS Watcher Native Smoke"
        packageName = "FsWatcherNativeSmoke"
        packageVersion = "1.0.0"
    }
}

val smokeNativeReportFile = layout.buildDirectory.file("reports/fs-watcher-native-smoke/report.json")
val nativeSmokeBinary =
    layout.buildDirectory.file("compose/tmp/main/graalvm/nativeCompile/fs-watcher-native-smoke.exe")

tasks.register<Exec>("smokeNative") {
    group = "verification"
    description = "Build and execute the fs-watcher native smoke binary, writing a JSON report."
    dependsOn("nativeImageCompile")

    inputs.file(nativeSmokeBinary)
    outputs.file(smokeNativeReportFile)

    doFirst {
        val reportFile = smokeNativeReportFile.get().asFile
        reportFile.parentFile.mkdirs()
        if (reportFile.exists()) {
            reportFile.delete()
        }
    }

    executable = nativeSmokeBinary.get().asFile.absolutePath
    args("--report", smokeNativeReportFile.get().asFile.absolutePath)
}
