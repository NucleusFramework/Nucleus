import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// E2E smoke for #513: Decompose navigation created on Dispatchers.Main under Tao, both on the
// pre-loop fallback thread and on the event-loop thread, and still rejected off the UI thread.
// Exits 0 on success, 1 on any wrong verdict:
//   ./gradlew :examples:decompose-smoke:run

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinxSerialization)
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(project(":graalvm-runtime"))
    implementation(compose.desktop.currentOs)
    // extensions-compose is what registers Decompose's Swing (EDT-only) MainThreadChecker.
    implementation(libs.decompose)
    implementation(libs.decompose.extensions.compose)
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
    mainClass = "dev.nucleusframework.decomposesmoke.MainKt"

    nativeDistributions {
        packageName = "decompose-smoke"
        packageVersion = "1.0.0"
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "decompose-smoke"
    }
}
