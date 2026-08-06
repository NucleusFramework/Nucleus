import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinxSerialization)
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
    implementation(kotlin("stdlib"))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
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

// Forward the opt-in `nucleus.e2e.*` properties to the test JVM so MacRealArtifactUpdateTest can be
// pointed at DMG/ZIP artifacts produced by a real packaging run. Read through the provider API so
// the values stay a declared configuration-cache input instead of being baked into the cache entry.
val e2eProperties = providers.systemPropertiesPrefixedBy("nucleus.e2e.")

tasks.withType<Test>().configureEach {
    systemProperties(e2eProperties.get())
    // The real-artifact E2E tests hold a whole installer (100 MB+) in the loopback host.
    maxHeapSize = "2g"
}

/**
 * Prints the exact production AppImage update script (used by
 * `scripts/e2e-appimage-gui-restart.sh`).
 *
 * ```
 * ./gradlew :updater-runtime:dumpLinuxAppImageUpdateScript \
 *   --args="'/tmp/new.AppImage' '/tmp/old.AppImage' 12345 '/tmp/update.log' true"
 * ```
 */
tasks.register<JavaExec>("dumpLinuxAppImageUpdateScript") {
    group = "verification"
    description = "Emit the production Linux AppImage update shell script to stdout"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.nucleusframework.updater.DumpLinuxAppImageUpdateScriptKt")
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.updater-runtime", publishVersion)

    pom {
        name.set("Nucleus Updater Runtime")
        description.set("Updater runtime library for the Nucleus Gradle plugin")
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
