import dev.nucleusframework.desktop.application.dsl.CompressionLevel
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
        // PGO (Oracle GraalVM): `runWithPgoInstrument` records graalvm/pgo/default.iprof, applied
        // automatically by every later build — no configuration needed. True PGO replaces
        // ML-inferred PGO. Opt out (pure -O3) with -Pnucleus.graalvm.pgo=off.
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
