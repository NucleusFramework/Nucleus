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
    api(project(":decorated-window-core"))
    api(project(":decorated-window-awt"))
    implementation(project(":core-runtime"))
    implementation(libs.compose.desktop.common)

    // An app ships exactly one backend at runtime — by construction (their
    // imports overlap, so coexistence is unsupported). We compile against
    // jni (which provides the AWT-bound DecoratedWindow signature, identical
    // to jbr's) and tao for the no-AWT path.
    compileOnly(project(":decorated-window-jni"))
    compileOnly(project(":decorated-window-tao"))
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
    coordinates("io.github.kdroidfilter", "nucleus.nucleus-application", publishVersion)

    pom {
        name.set("Nucleus Application")
        description.set(
            "Unified entry point picking the decorated-window backend " +
                "(JBR/JNI AWT or no-AWT Tao) and exposing a backend-agnostic window handle.",
        )
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
