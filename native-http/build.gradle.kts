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
    coordinates("dev.nucleusframework", "nucleus.native-http", publishVersion)

    pom {
        name.set("Nucleus Native HTTP")
        description.set("java.net.http.HttpClient factory with native OS certificate trust")
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
