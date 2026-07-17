import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.NativeImageMarch
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization
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
    implementation(compose.material3)
    implementation(project(":core-runtime"))
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(libs.coroutines.core)
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
    mainClass = "benchmarkdemo.MainKt"

    // Benchmark orchestration: let run-all.sh point the run/runRelease fork at a specific JDK
    // (jvm-c2 on stock HotSpot, jvm-graal on GraalVM JIT) without moving the Gradle daemon off
    // its stable build JDK. The forked process is what gets measured — not the daemon.
    providers.gradleProperty("runJavaHome").orNull?.let { javaHome = it }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.ORACLE
        imageName = "benchmark-demo"
        // The whole point of this demo: -O3 (Oracle GraalVM only) so the AOT
        // build is compiled at peak-runtime optimization for the JIT-vs-AOT shootout.
        // Override with -Popt=2 (native-image default level) or -Popt=s (-Os, size-optimized).
        optimization =
            when (providers.gradleProperty("opt").orNull) {
                "2" -> NativeImageOptimization.LEVEL_2
                "s" -> NativeImageOptimization.SIZE
                else -> NativeImageOptimization.LEVEL_3
            }
        // ISA parity with what Swift/Rust actually ship on macOS ARM: their target-triple
        // baseline is the M1 ISA (AES/SHA/LSE/CRC32...), so `native` here — GraalVM's default
        // `compatibility` targets bare ARMv8.0, a portability no macOS app needs.
        march = NativeImageMarch.NATIVE
        // PGO (Oracle GraalVM): build instrumented with -Ppgo=instrument, run the binary with
        // --headless to record default.iprof into pgo/, then rebuild — the profile is applied
        // automatically whenever pgo/default.iprof exists. True PGO replaces ML-inferred PGO.
        val pgoProfile = layout.projectDirectory.file("pgo/default.iprof").asFile
        when (providers.gradleProperty("pgo").orNull) {
            "instrument" -> buildArgs.add("--pgo-instrument")
            "off" -> Unit // pure -O3 (Oracle's default ML-inferred profile), ignore any recorded iprof
            else -> if (pgoProfile.exists()) buildArgs.add("--pgo=${pgoProfile.absolutePath}")
        }
    }

    buildTypes {
        release {
            proguard {
                version = "7.9.0"
                isEnabled = true
                optimize = true
            }
        }
    }


    nativeDistributions {
        compressionLevel = CompressionLevel.Maximum
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "NucleusBenchmark"
        packageVersion = "1.0.0"
        homepage = "https://github.com/NucleusFramework/Nucleus"

        macOS {
            bundleID = "dev.nucleusframework.benchmark"
            dockName = "NucleusBenchmark"
        }
    }
}
