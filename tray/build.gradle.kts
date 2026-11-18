import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("nucleus.native-module")
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    implementation(project(":core-runtime"))
    implementation(project(":darkmode-detector"))
    // api: NucleusApplicationScope is the receiver of the public TrayApp API.
    api(project(":nucleus-application"))
    implementation(project(":decorated-window-tao"))
    implementation(libs.compose.desktop.common)
    implementation(libs.compose.components.resources)
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.jna.jpms)
    testImplementation(libs.jna.platform.jpms)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("dev.nucleusframework.window.ExperimentalNucleusApi")
    }
}

tasks.test {
    useJUnitPlatform()
    // The Windows torture suite drives the real notification area; keep it to one JVM.
    maxParallelForks = 1
    systemProperty("nucleus.tray.torture.seed", providers.gradleProperty("traySeed").orNull ?: "")
    systemProperty("nucleus.tray.torture.iterations", providers.gradleProperty("trayIterations").orNull ?: "")
    // macOS: freed memory is overwritten, so a use after free in the AppKit backend crashes the
    // test JVM instead of reading stale but plausible values.
    if (System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
    ) {
        environment("MallocScribble", "1")
    }
}

nucleusNative {
    macos("nucleus_tray")
    linux("nucleus_tray")
    windows("nucleus_tray")
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.tray", publishVersion)

    pom {
        name.set("Nucleus Tray")
        description.set(
            "Native system tray icon, menu and anchored TrayApp popup for Compose Desktop " +
                "(Shell_NotifyIcon, NSStatusItem, StatusNotifierItem)",
        )
        url.set("https://github.com/NucleusFramework/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("nucleusframework")
                name.set("NucleusFramework")
                url.set("https://github.com/NucleusFramework")
            }
        }

        scm {
            url.set("https://github.com/NucleusFramework/Nucleus")
            connection.set("scm:git:git://github.com/NucleusFramework/Nucleus.git")
            developerConnection.set("scm:git:ssh://git@github.com/NucleusFramework/Nucleus.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
