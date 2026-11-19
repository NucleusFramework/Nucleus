import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    api(project(":tray"))
    // api: NucleusApplicationScope is the receiver of the public TrayApp API.
    api(project(":nucleus-application"))
    implementation(project(":core-runtime"))
    implementation(project(":decorated-window-tao"))
    implementation(libs.compose.desktop.common)
    implementation(libs.compose.components.resources)
    implementation(libs.coroutines.core)

    testImplementation(testFixtures(project(":tray")))
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
        optIn.add("dev.nucleusframework.tray.InternalNucleusTrayApi")
    }
}

tasks.test {
    useJUnitPlatform()
    // The E2E suites drive the real notification area / panel; keep them to one JVM.
    maxParallelForks = 1
    systemProperty("nucleus.tray.torture.seed", providers.gradleProperty("traySeed").orNull ?: "")
    systemProperty("nucleus.tray.torture.iterations", providers.gradleProperty("trayIterations").orNull ?: "")
    // CI: an E2E that cannot run (no display, no dbus-daemon) fails instead of skipping.
    systemProperty("nucleus.tray.e2e.required", providers.gradleProperty("trayE2eRequired").orNull ?: "")
    if (System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
    ) {
        environment("MallocScribble", "1")
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.tray-app", publishVersion)

    pom {
        name.set("Nucleus Tray App")
        description.set("TrayApp: a Compose popup window anchored to its tray icon, on top of nucleus.tray")
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
