import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("nucleus.native-module")
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
    implementation(project(":core-runtime"))
    api(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
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

// macOS only: StoreKit 2 (Swift) behind an Objective-C JNI shim. Windows and Linux answer
// "unsupported" from Kotlin, with no library.
nucleusNative {
    macos("nucleus_macstore", "Compiles the StoreKit 2 bridge (Swift + Objective-C JNI shim) into macOS dylibs")
}

// scripts/mac-app-store-e2e.sh runs MacAppStoreE2E (test sources) inside a development .app
// whose launcher reads its classpath from this file, so a rebuild needs no repackaging.
tasks.register("e2eClasspath") {
    val classpath = sourceSets.test.get().runtimeClasspath
    val output = layout.buildDirectory.file("e2e/classpath.txt")
    inputs.files(classpath)
    outputs.file(output)
    doLast { output.get().asFile.writeText(classpath.files.joinToString(":")) }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.mac-app-store", publishVersion)

    pom {
        name.set("Nucleus Mac App Store")
        description.set("In-app purchases for JVM desktop apps on the Mac App Store (StoreKit 2).")
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
