import dev.detekt.gradle.Detekt

plugins {
    `java-base`
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.pluginPublish) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.lighthouse)
    alias(libs.plugins.versionCheck)
}

val resolvedVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

group = property("GROUP").toString()
version = resolvedVersion

dependencyLocking {
    lockAllConfigurations()
}

fun Project.configurePluginBuildQuality() {
    group = property("GROUP").toString()
    version = resolvedVersion

    apply {
        plugin(
            rootProject.libs.plugins.detekt
                .get()
                .pluginId,
        )
        plugin(
            rootProject.libs.plugins.ktlint
                .get()
                .pluginId,
        )
    }

    ktlint {
        debug.set(false)
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        enableExperimentalRules.set(true)
        filter {
            exclude("**/generated/**")
            include("**/kotlin/**")
        }
    }

    detekt {
        config.setFrom(rootProject.files("../config/detekt/detekt.yml"))
        ignoreFailures = true
    }
}

configurePluginBuildQuality()

project(":plugin") {
    configurePluginBuildQuality()
}

lighthouse {
    enableTestCoverageCheck.set(false)
}

tasks.withType<Detekt>().configureEach {
    jvmTarget.set("11")
    reports {
        html.required.set(true)
        html.outputLocation.set(file("build/reports/detekt.html"))
    }
}

tasks.named<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}
