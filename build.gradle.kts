import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import dev.detekt.gradle.Detekt

plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.graalvmNative) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.versionCheck)
}

val demoProjects =
    setOf("example", "jewel-sample", "system-info-demo", "sample-cmp", "scheduler-demo", "service-management-demo")

subprojects {
    if (name !in demoProjects) {
        apply {
            plugin(
                rootProject.libs.plugins.detekt
                    .get()
                    .pluginId,
            )
        }
    }
    apply {
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

    if (name !in demoProjects) {
        detekt {
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        }

        tasks.withType<Detekt>().configureEach {
            jvmTarget.set("21")
        }
    }
}

tasks.withType<Detekt>().configureEach {
    jvmTarget.set("21")
    reports {
        html.required.set(true)
        html.outputLocation.set(file("build/reports/detekt.html"))
    }
}

tasks.withType<DependencyUpdatesTask> {
    rejectVersionIf {
        candidate.version.isNonStable()
    }
}

fun String.isNonStable() = "^[0-9,.v-]+(-r)?$".toRegex().matches(this).not()

tasks.register("clean", Delete::class.java) {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("reformatAll") {
    description = "Reformat all the Kotlin Code"

    dependsOn("ktlintFormat")
    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:ktlintFormat"))
}

tasks.register("preMerge") {
    description = "Runs all the tests/verification tasks on both top level and included build."

    dependsOn(":core-runtime:check")
    dependsOn(":aot-runtime:check")
    dependsOn(":updater-runtime:check")
    dependsOn(":darkmode-detector:check")
    dependsOn(":native-ssl:check")
    dependsOn(":native-http:check")
    dependsOn(":native-http-okhttp:check")
    dependsOn(":native-http-ktor:check")
    dependsOn(":decorated-window-core:check")
    dependsOn(":decorated-window-jbr:check")
    dependsOn(":decorated-window-jni:check")
    dependsOn(":decorated-window-jewel:check")
    dependsOn(":decorated-window-material2:check")
    dependsOn(":decorated-window-material3:check")
    dependsOn(":graalvm-runtime:check")
    dependsOn(":system-color:check")
    dependsOn(":energy-manager:check")
    dependsOn(":linux-hidpi:check")
    dependsOn(":taskbar-progress:check")
    dependsOn(":freedesktop-icons:check")
    dependsOn(":notification-linux:check")
    dependsOn(":notification-macos:check")
    dependsOn(":launcher-linux:check")
    dependsOn(":scheduler:check")
    dependsOn(":example:check")
    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:check"))
    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:validatePlugins"))
}

tasks.register("cleanAllNative", Delete::class.java) {
    description = "Deletes all generated native files"
    group = "build"

    subprojects {
        delete(layout.projectDirectory.dir("src/main/resources/nucleus/native"))
        delete(layout.projectDirectory.dir("src/main/native/target"))
        delete(
            fileTree(layout.projectDirectory.dir("src/main/native")) {
                include("**/*.obj", "**/*.exp", "**/*.lib", "**/*.pdb")
            },
        )
    }
    delete(rootProject.layout.projectDirectory.dir("sample-tao/src/main/native/windows/packages"))
    delete(rootProject.layout.projectDirectory.file("sample-tao/src/main/native/windows/nuget.exe"))
    delete(rootProject.layout.projectDirectory.file("sample-tao/src/main/native/windows/build_log.txt"))
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}
