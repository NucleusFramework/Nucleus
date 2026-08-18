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

// Controlled repro for issue #264 residual portal bugs (see src/repro/...).
val repro by sourceSets.creating {
    kotlin.srcDir("src/repro/kotlin")
}

configurations {
    named("reproImplementation") { extendsFrom(configurations["implementation"]) }
    named("reproRuntimeOnly") { extendsFrom(configurations["runtimeOnly"]) }
}

dependencies {
    implementation(project(":core-runtime"))
    add("reproImplementation", sourceSets["main"].output)
    testImplementation(kotlin("test"))
}

val reproOrder = providers.gradleProperty("reproOrder").orElse("a")

tasks.register<JavaExec>("runIssue264Repro") {
    group = "verification"
    description = "Reproduce issue #264 portal shortcut_id / multi-BindShortcuts bugs (Wayland)"
    dependsOn(tasks.named("compileReproKotlin"), tasks.named("processResources"))
    classpath = repro.runtimeClasspath
    mainClass.set("dev.nucleusframework.globalhotkey.repro.Issue264ReproKt")
    systemProperty("repro.order", reproOrder.get())
    // Real portal path needs a session bus + Wayland; do not force headless.
    isIgnoreExitValue = true
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
    windows("nucleus_global_hotkey")
    linux("nucleus_global_hotkey")
    macos("nucleus_global_hotkey")
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.global-hotkey", publishVersion)

    pom {
        name.set("Nucleus Global Hotkey")
        description.set(
            "Cross-platform global hotkey (system-wide keyboard shortcuts) for JVM desktop applications via JNI",
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
