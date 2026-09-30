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
    // Window/dialog wrappers only add styling on top of nucleus-application's
    // Tao-backed window; the app brings both at runtime.
    compileOnly(project(":decorated-window-tao"))
    compileOnly(project(":nucleus-application"))
    api(project(":core-runtime"))
    api(libs.compose.desktop.common)
    implementation(libs.compose.material3)
    testImplementation(kotlin("test"))
    testImplementation(project(":decorated-window-core"))
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.material3)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.compose.get()}")
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

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.decorated-window-material3", publishVersion)

    pom {
        name.set("Nucleus Material Decorated Window")
        description.set("Material 3 integration for Nucleus Decorated Window")
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
