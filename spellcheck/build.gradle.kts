import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
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
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
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

nucleusNative {
    linux("nucleus_spellcheck")
    macos("nucleus_spellcheck")
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.spellcheck", publishVersion)

    pom {
        name.set("Nucleus Spellcheck")
        description.set(
            "Spell checker for Compose text fields: Linux Hunspell (system dictionaries " +
                "via dlopen) and macOS NSSpellChecker. No-op on Windows and when the " +
                "native engine or a dictionary is unavailable.",
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
