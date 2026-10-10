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
    // Window/dialog wrappers only add styling on top of nucleus-application's
    // Tao-backed window; the app brings both at runtime.
    compileOnly(project(":decorated-window-tao"))
    compileOnly(project(":nucleus-application"))
    api(project(":core-runtime"))
    api(libs.compose.desktop.common)
    implementation(libs.jewel.foundation)
    implementation(libs.jewel.ui)

    // The Jewel spellcheck/context-menu integration is exercised through a real IntUiTheme.
    testImplementation(project(":decorated-window-tao"))
    testImplementation(project(":nucleus-application"))
    testImplementation(libs.junit)
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.compose.get()}")
    testImplementation(libs.jewel.int.ui.standalone) {
        exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
    }
    testImplementation(libs.intellij.icons)
    testImplementation(libs.intellij.icons.api)
    testImplementation(libs.intellij.icons.impl)
    testImplementation(libs.jna.jpms)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

// Compiled to class file 69: the tests run on a 25 JVM whatever JDK runs Gradle.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.decorated-window-jewel", publishVersion)

    pom {
        name.set("Nucleus Jewel Decorated Window")
        description.set("Jewel (IntelliJ theme) integration for Nucleus Decorated Window")
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
