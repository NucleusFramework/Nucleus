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
    // Compile against all backends — consumer picks one at runtime:
    //  :decorated-window-jbr (JBR), :decorated-window-jni (any JVM), or
    //  :decorated-window-tao (no-AWT native).
    compileOnly(project(":decorated-window-jbr"))
    compileOnly(project(":decorated-window-tao"))
    compileOnly(project(":nucleus-application"))
    api(project(":core-runtime"))
    api(libs.compose.desktop.common)
    implementation(libs.jewel.foundation)
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
    coordinates("dev.nucleusframework", "nucleus.decorated-window-jewel", publishVersion)

    pom {
        name.set("Nucleus Jewel Decorated Window")
        description.set("Jewel (IntelliJ theme) integration for Nucleus Decorated Window")
        url.set("https://github.com/nucleusframework/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("nucleusframework")
                name.set("nucleusframework")
                url.set("https://github.com/nucleusframework")
            }
        }

        scm {
            url.set("https://github.com/nucleusframework/Nucleus")
            connection.set("scm:git:git://github.com/nucleusframework/Nucleus.git")
            developerConnection.set("scm:git:ssh://git@github.com/nucleusframework/Nucleus.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
