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
    api(project(":aot-runtime"))
    // api: nucleusApplication bridges Compose's isSystemInDarkTheme() to the
    // reactive OS detector, so consumers always get darkmode-detector on the
    // compile + runtime classpath (and may call isSystemInDarkMode() directly).
    api(project(":darkmode-detector"))
    implementation(project(":core-runtime"))
    implementation(project(":graalvm-runtime"))
    // Spellcheck (Linux Hunspell, macOS NSSpellChecker; no-op elsewhere).
    // `api` because SpellcheckContextMenu / NucleusSpellcheckInstaller expose SpellcheckSession.
    api(project(":spellcheck"))
    // api: NucleusApplicationScope extends Compose's ApplicationScope, so the
    // supertype must be visible on consumers' compile classpath.
    api(libs.compose.desktop.common)

    // An app ships exactly one backend at runtime — by construction (their
    // imports overlap, so coexistence is unsupported). We compile against
    // jni (which provides the AWT-bound DecoratedWindow signature, identical
    // to jbr's) and tao for the no-AWT path.
    compileOnly(project(":decorated-window-jni"))
    compileOnly(project(":decorated-window-tao"))

    testImplementation(libs.junit)
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.compose.get()}")
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

/**
 * Live process E2E for the system-theme bridge (needs a display / D-Bus on Linux).
 * Not part of `check` — run explicitly: `./gradlew :nucleus-application:systemThemeE2E`
 */
tasks.register<JavaExec>("spellcheckConsumer") {
    group = "verification"
    description =
        "Runs the in-repo spellcheck consumer (nucleusApplication installer + shipped check/suggest)"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.nucleusframework.application.spellcheck.SpellcheckConsumerMainKt")
}

tasks.register<JavaExec>("systemThemeE2E") {
    group = "verification"
    description =
        "Boots a real Compose application under ProvideNucleusSystemTheme and " +
        "asserts isSystemInDarkTheme() matches the native detector"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.nucleusframework.application.NucleusApplicationSystemThemeE2EMainKt")
    systemProperty(
        "systemThemeE2E.report",
        layout.buildDirectory
            .file("reports/system-theme-e2e.report")
            .get()
            .asFile
            .absolutePath,
    )
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.nucleus-application", publishVersion)

    pom {
        name.set("Nucleus Application")
        description.set(
            "Unified entry point picking the decorated-window backend " +
                "(JBR/JNI AWT or no-AWT Tao) and exposing a backend-agnostic window handle.",
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
