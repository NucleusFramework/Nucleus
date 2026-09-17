import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("nucleus.native-module")
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
    api(project(":core-runtime"))
    api(libs.compose.desktop.common)
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.compose.get()}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

tasks.test {
    useJUnitPlatform()
}

nucleusNative {
    macos("nucleus_systemcolor")
    linux("nucleus_systemcolor")
    windows("nucleus_systemcolor")
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.system-color", publishVersion)

    pom {
        name.set("Nucleus System Color")
        description.set("Reactive system accent color and contrast mode detection for Compose Desktop")
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
