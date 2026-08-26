import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import dev.detekt.gradle.Detekt
import dev.nucleusframework.gradle.NativeTarget
import org.apache.tools.ant.taskdefs.condition.Os
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
    alias(libs.plugins.kover)
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
            "watermark-demo",
            "rect-stress-demo",
            "widget-demo",
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

// The per-module `buildNative*` tasks themselves are wired by the
// `nucleus.native-module` convention plugin (see buildSrc).
val buildNative by tasks.registering {
    group = "build"
    description = "Builds native libraries for the current host platform."
}

tasks.register("watchNative") {
    group = "build"
    description = "Builds native libraries; run with --continuous to rebuild on native source changes."
    dependsOn(buildNative)
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

    if (!isDemoProject) {
        // Library modules only. Examples stay out of the aggregated report so
        // demo UI does not dilute (or inflate) published-runtime coverage.
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            apply(plugin = rootProject.libs.plugins.kover.get().pluginId)
            rootProject.dependencies.add("kover", project(path))
        }
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            apply(plugin = rootProject.libs.plugins.kover.get().pluginId)
            rootProject.dependencies.add("kover", project(path))
        }
    }

    // Sources are UTF-8, but `javac` and `javadoc` default to the *platform*
    // encoding, which is only UTF-8 where the JVM's default charset happens to
    // be. On a Windows machine with a non-Western system locale it is a legacy
    // code page (windows-31j on Japanese, GBK on Simplified Chinese, ...), and
    // any non-ASCII character in a Java source file — the em dash in
    // `TaoTransferableAccess`, for instance — becomes
    // "unmappable character for encoding", which is a hard error in `javadoc`.
    // That makes `publishToMavenLocal` fail for those developers even though CI
    // (Linux/macOS, UTF-8 by default) is green. Stating the encoding explicitly
    // makes the build reproducible regardless of the host locale.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).apply {
            docEncoding = "UTF-8"
            charSet = "UTF-8"
        }
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
    buildNative.configure {
        dependsOn(
            subprojects.flatMap { project ->
                NativeTarget.entries
                    .filter { it.isHost }
                    .mapNotNull { project.tasks.findByName(it.taskName) }
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
    description =
        "Runs verification for every published library module plus the flagship demo " +
        "and the included Gradle plugin. New library modules are picked up automatically."

    // Every non-example subproject: compile + unit tests + detekt/ktlint + apiCheck
    // (BCV wires apiCheck into each library module's `check`). Provider so the
    // set of modules is resolved after projects are created and stays in sync
    // with settings.gradle.kts (no hand-maintained allow-list).
    dependsOn(
        provider {
            subprojects
                // Skip the examples umbrella and every demo under it. The
                // umbrella project has no `check` task; demos are opt-in
                // (only nucleus-demo is wired below as a consumer smoke).
                .filter { !it.path.startsWith(":examples") }
                .filter { it.tasks.findByName("check") != null }
                .map { it.tasks.named("check") }
        },
    )

    // Flagship demo as a consumer smoke check (compile/test).
    dependsOn(":examples:nucleus-demo:check")
    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:check"))
    dependsOn(gradle.includedBuild("plugin-build").task(":plugin:validatePlugins"))
}

// Aggregated coverage for every published runtime module. Local `check` /
// `preMerge` do not run koverVerify — coverage is informational only
// (`./gradlew koverHtmlReport` / `koverLog`). GraalVM @TargetClass
// substitutions stay IN.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "dev.nucleusframework.sfsymbols.*",
                    "dev.nucleusframework.freedesktop.icons.*",
                    "dev.nucleusframework.window.icons.*",
                )
            }
        }
        total {
            verify {
                onCheck = false
            }
            html {
                title = "Nucleus published runtime"
                htmlDir.set(layout.buildDirectory.dir("reports/kover/html"))
            }
            xml {
                xmlFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
            }
            log {
                header = "Nucleus published runtime line coverage"
                format = "<entity> line coverage: <value>%"
            }
            binary {
                file.set(layout.buildDirectory.file("reports/kover/report.bin"))
            }
            // Merged when :decorated-window-tao:taoHeadfulTest has been run
            // (Kover agent on the JavaExec). A missing/empty file is skipped
            // so a clean checkout can still produce a unit-test-only report.
            val headfulIc = file("decorated-window-tao/build/kover/bin-reports/taoHeadful.ic")
            if (headfulIc.isFile && headfulIc.length() > 0L) {
                additionalBinaryReports.add(headfulIc)
            }
            // Optional extra binary reports dropped into this directory
            // (e.g. a local multi-OS merge). Configuration-time listing
            // is enough: the files must exist before Gradle starts.
            val crossOsDir = file("build/kover/cross-os")
            if (crossOsDir.isDirectory) {
                crossOsDir
                    .walkTopDown()
                    .filter { it.isFile && (it.extension == "ic" || it.extension == "bin") && it.length() > 0L }
                    .forEach { additionalBinaryReports.add(it) }
            }
        }
    }
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}
