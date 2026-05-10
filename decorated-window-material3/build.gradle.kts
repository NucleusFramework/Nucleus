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
    // Compile against all backends — consumers pick exactly one at runtime:
    //  :decorated-window-jbr (JBR), :decorated-window-jni (any JVM), or
    //  :decorated-window-tao (no-AWT native).
    compileOnly(project(":decorated-window-jbr"))
    compileOnly(project(":decorated-window-tao"))
    compileOnly(project(":nucleus-application"))
    api(project(":core-runtime"))
    api(libs.compose.desktop.common)
    implementation(libs.compose.material3)
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
    coordinates("io.github.kdroidfilter", "nucleus.decorated-window-material3", publishVersion)

    pom {
        name.set("Nucleus Material Decorated Window")
        description.set("Material 3 integration for Nucleus Decorated Window")
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
