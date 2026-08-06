import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import dev.detekt.gradle.Detekt
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.graalvmNative) apply false
    // Freezes public ABI for every published library module: `apiCheck` fails on
    // any change to public FQNs/signatures vs the checked-in `api/*.api` dumps
    // (same harness as decorated-window-tao — see README project status).
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.versionCheck)
}

apiValidation {
    // Demo / sample apps are not published; skip ABI dumps for them.
    // Names match the last segment of include(":examples:...") in settings.
    ignoredProjects.addAll(
        listOf(
            "nucleus-demo",
            "compose-demo",
            "tao-demo",
            "swing-tao-demo",
            "zstd-demo",
            "jni-demo",
            "shared",
            "jewel-demo",
            "cmp-demo",
            "scheduler-demo",
            "service-management-demo",
            "system-info-demo",
            "fs-watcher-smoke",
            "orphan-reflect-smoke",
            "extra-launcher-demo",
            "benchmark-demo",
            "gstreamer-demo",
            "mediafoundation-demo",
            "avfoundation-demo",
            "tao-native-test",
            "window-scaffold-demo",
            // BCV 0.18.1's bundled ASM cannot read JVM 25 class files (major 69).
            // Module still uses explicitApi(); re-enable once BCV/KGP ABI supports it.
            "decorated-window-jewel",
        ),
    )
    // TaoTransferableAccess lives in androidx.compose.ui.draganddrop purely to
    // reach Compose's internal AwtDragAndDropTransferable (Java friend-package
    // access). Implementation detail of decorated-window-tao, not public ABI.
    ignoredPackages.add("androidx.compose.ui.draganddrop")
}

val nativeBuildTaskPrefix = "buildNative"

val buildNative by tasks.registering {
    group = "build"
    description = "Builds native libraries for the current host platform."
}

tasks.register("watchNative") {
    group = "build"
    description = "Builds native libraries; run with --continuous to rebuild on native source changes."
    dependsOn(buildNative)
}

fun isCurrentHostNativeTask(taskName: String): Boolean =
    when {
        taskName.contains("Windows", ignoreCase = true) -> Os.isFamily(Os.FAMILY_WINDOWS)
        taskName.contains("Mac", ignoreCase = true) -> Os.isFamily(Os.FAMILY_MAC)
        taskName.contains("Linux", ignoreCase = true) -> Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC)
        else -> true
    }

subprojects {
    val isDemoProject = path.startsWith(":examples:")
    if (!isDemoProject) {
        apply {
            plugin(
                rootProject.libs.plugins.detekt
                    .get()
                    .pluginId,
            )
        }
        // JetBrains convention: every public declaration must state its
        // visibility (and return type) explicitly so the public surface can
        // only change deliberately — enforced together with BCV apiCheck.
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            extensions.configure<KotlinProjectExtension>("kotlin") {
                explicitApi()
            }
        }
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinProjectExtension>("kotlin") {
                explicitApi()
            }
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

    if (!isDemoProject) {
        detekt {
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        }

        tasks.withType<Detekt>().configureEach {
            jvmTarget.set("25")
        }
    }
}

gradle.projectsEvaluated {
    allprojects {
        tasks.withType<Exec>().configureEach {
            val taskName = name
            if (!taskName.startsWith(nativeBuildTaskPrefix)) return@configureEach
            val isHostTask = isCurrentHostNativeTask(taskName)

            val nativeSources =
                fileTree("src/main/native") {
                    include("Cargo.toml", "Cargo.lock", "build.rs", "src/**")
                    when {
                        taskName.contains("Windows", ignoreCase = true) -> include("windows/**")
                        taskName.contains("Mac", ignoreCase = true) -> include("macos/**")
                        taskName.contains("Linux", ignoreCase = true) -> include("linux/**")
                    }
                    exclude("target/**", "vendor/**")
                }

            if (System.getenv("CI") != "true") {
                enabled = isHostTask
                setOnlyIf("native build task matches the current host OS") {
                    isHostTask
                }
            }
            inputs
                .files(nativeSources)
                .withPropertyName("nativeSources")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    buildNative.configure {
        dependsOn(
            allprojects.flatMap { project ->
                project.tasks
                    .matching { task ->
                        task is Exec &&
                            task.name.startsWith(nativeBuildTaskPrefix) &&
                            isCurrentHostNativeTask(task.name)
                    }.toList()
            },
        )
    }
}

tasks.withType<Detekt>().configureEach {
    jvmTarget.set("25")
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

tasks.register("cleanNativeLibs", Delete::class.java) {
    group = "cleanup"
    description = "Cleans all native libraries from resources and system cache"

    allprojects.forEach { p ->
        delete(p.layout.projectDirectory.dir("src/main/resources/nucleus/native"))
    }

    val os = System.getProperty("os.name").lowercase()
    val userHome = System.getProperty("user.home")
    val cachePath =
        when {
            os.contains("win") -> System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
            os.contains("mac") -> "$userHome/Library/Caches"
            else -> System.getenv("XDG_CACHE_HOME") ?: "$userHome/.cache"
        }
    delete(file(cachePath).resolve("nucleus/native"))

    doFirst {
        println("Cleaning all native libraries from resources and system cache...")
    }
}

tasks.register("reformatAll") {
    description = "Reformat all the Kotlin Code"

    dependsOn("ktlintFormat")
    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:ktlintFormat"))
}

val publishAllToMavenLocal by tasks.registering {
    group = "publishing"
    description = "Publishes all runtime libraries and the Gradle plugin to Maven Local."

    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:publishToMavenLocal"))
}

gradle.projectsEvaluated {
    publishAllToMavenLocal.configure {
        dependsOn(subprojects.mapNotNull { it.tasks.findByName("publishToMavenLocal") })
    }
}

tasks.register<Exec>("publishDevToMavenLocal") {
    group = "publishing"
    description = "Publishes all runtime libraries and the Gradle plugin to Maven Local with version 'dev'."

    workingDir = rootDir
    // The publish version is resolved from GITHUB_REF at configuration time (see each module's
    // build.gradle.kts), so re-invoke the build with it set to force version "dev" everywhere.
    environment("GITHUB_REF", "refs/tags/vdev")

    val gradlew =
        if (Os.isFamily(Os.FAMILY_WINDOWS)) listOf("cmd", "/c", "gradlew.bat") else listOf("./gradlew")
    commandLine(gradlew + listOf("publishAllToMavenLocal", "--no-configuration-cache"))
}

tasks.register("preMerge") {
    description = "Runs all the tests/verification tasks on both top level and included build."

    // Binary-compatibility freeze for every published library module (api/*.api).
    dependsOn("apiCheck")
    dependsOn(":core-runtime:check")
    dependsOn(":aot-runtime:check")
    dependsOn(":updater-runtime:check")
    dependsOn(":darkmode-detector:check")
    dependsOn(":native-ssl:check")
    dependsOn(":native-http:check")
    dependsOn(":native-http-okhttp:check")
    dependsOn(":native-http-ktor:check")
    dependsOn(":decorated-window-core:check")
    dependsOn(":decorated-window-tao:check")
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
    dependsOn(":fs-watcher:check")
    dependsOn(":examples:nucleus-demo:check")
    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:check"))
    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:validatePlugins"))
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}
