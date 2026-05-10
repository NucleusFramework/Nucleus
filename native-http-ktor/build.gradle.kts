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
    api(project(":native-ssl"))
    api(libs.ktor.client.core)
    // Engine-agnostic: user picks their own engine; we only need them on compile classpath
    // to configure SSL when present at runtime.
    compileOnly(libs.ktor.client.cio)
    compileOnly(libs.ktor.client.java)
    compileOnly(libs.ktor.client.okhttp)
    compileOnly(libs.ktor.client.apache5)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.java)
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

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.native-http-ktor", publishVersion)

    pom {
        name.set("Nucleus Native HTTP Ktor")
        description.set("Engine-agnostic Ktor HttpClient extension with native OS certificate trust")
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
