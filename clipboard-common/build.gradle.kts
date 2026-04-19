import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
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
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test"))
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

mavenPublishing {
    coordinates("io.github.kdroidfilter", "nucleus.clipboard-common", publishVersion)

    pom {
        name.set("Nucleus Clipboard Common")
        description.set("Cross-platform rich clipboard API (text, HTML, RTF, images, files) with change watcher")
        url.set("https://github.com/kdroidFilter/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("kdroidfilter")
                name.set("kdroidFilter")
                url.set("https://github.com/kdroidFilter")
            }
        }

        scm {
            url.set("https://github.com/kdroidFilter/Nucleus")
            connection.set("scm:git:git://github.com/kdroidFilter/Nucleus.git")
            developerConnection.set("scm:git:ssh://git@github.com/kdroidFilter/Nucleus.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
