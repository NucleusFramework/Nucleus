import dev.nucleusframework.desktop.application.dsl.NativeImageMarch
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// GraalVM native test runner for the Tao backend: compiles the stage-1
// offscreen battery and the stage-2 headful window suite into a native image
// and runs them (exit code = failures). CI runs `runGraalvmNative` per OS so
// the native binary is actually EXECUTED, not just compiled.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":decorated-window-tao"))
    // The suites live in decorated-window-tao's test source set; consumed as a
    // classes jar through the module's taoTestArtifacts configuration, which
    // also carries what those classes need at run time (kotlin.test, Compose
    // Desktop, Material 3) — so a dependency added to that test source set
    // reaches this image without being repeated here.
    implementation(project(path = ":decorated-window-tao", configuration = "taoTestArtifacts"))
    implementation(project(":core-runtime"))
    implementation(project(":graalvm-runtime"))
    implementation(compose.desktop.currentOs)
    // Regression fixture for issue #443: an SLF4J 2.x backend that must initialize at
    // RUN time. If anything on the classpath restores `--initialize-at-build-time=org.slf4j`,
    // the native-image build fails on LogbackMDCAdapter in the image heap.
    implementation(libs.logback.classic)
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
    mainClass = "dev.nucleusframework.taonativetest.MainKt"

    nativeDistributions {
        packageName = "tao-native-test"
        packageVersion = "1.0.0"
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "tao-native-test"
        march =
            when (project.findProperty("nativeMarch")) {
                "compatibility" -> NativeImageMarch.COMPATIBILITY
                else -> NativeImageMarch.NATIVE
            }
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
        )
    }
}
