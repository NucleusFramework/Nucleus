import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("nucleus.native-module")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.vanniktechMavenPublish)
    // BCV + explicitApi are applied for all library modules from the root
    // build.gradle.kts (api/decorated-window-tao.api baseline still applies).
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    api(project(":decorated-window-core"))
    implementation(project(":core-runtime"))
    // Compose `Modifier.keepScreenOn()` is a no-op on desktop unless the
    // scene's PlatformContext implements `isKeepScreenOnEnabled`. Tao owns
    // that context and forwards it to EnergyManager.
    implementation(project(":energy-manager"))
    implementation(libs.compose.desktop.common)
    // Compose Hot Reload interop (TaoHotReloadBridge). compileOnly: these
    // artifacts are only referenced when running under the hot-reload agent,
    // which puts them on the runtime classpath itself (the plugin adds
    // runtime-jvm — which brings devtools-api — and the agent jar brings the
    // `agent`/`core`/`orchestration` classes). Used only by `trackWindow`
    // (WindowsState / orchestration publishing), not by any wrapping.
    compileOnly(libs.hot.reload.agent)
    compileOnly(libs.hot.reload.core)
    compileOnly(libs.hot.reload.orchestration)
    compileOnly(libs.hot.reload.devtools.api)
    testImplementation(kotlin("test"))
    // Skiko native runtime for the opt-in real-window smoke test
    testImplementation(compose.desktop.currentOs)
    // The Material 3 AlertDialog the headful appearance film compares against nucleus-demo
    testImplementation(libs.compose.material3)
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

// #636 regression guard: the Compose applier-mismatch diagnostic is a warning,
// so ComposableTargetIsolationFixture would silently rot. Escalate it to an
// error for the test compilation, where that fixture lives.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.freeCompilerArgs.add("-Xwarning-level=COMPOSE_APPLIER_CALL_MISMATCH:error")
}

// ── Native build ────────────────────────────────────────────────────────────
// Tao + jni crate + per-platform helpers (Metal on macOS, WGL + WndProc deco
// on Windows). Native binaries ship in src/main/resources/nucleus/native/.

// The whole crate (src/main/native/{Cargo.toml,src}) plus the per-OS C helpers
// under src/main/native/<os> are tracked as inputs by the convention plugin, so
// touching a shared header alone still invalidates the task.
nucleusNative {
    macos("nucleus_tao", "Compiles the Rust JNI bridge into a macOS dylib (arm64 + x86_64)")
    windows("nucleus_tao", "Compiles the Rust JNI bridge + WGL/Deco helpers into Windows DLLs")
    linux("nucleus_tao", "Compiles the Rust JNI bridge + EGL helper into Linux .so libraries")
}

// ── macOS standalone-popup smoke check ──────────────────────────────────────
// AppKit requires the NSPanel to be created on the macOS main thread. Gradle's
// test worker runs tests off the main thread, so the macOS smoke check runs as
// a main() via JavaExec (process main thread = macOS main thread). Windows uses
// the in-process JUnit test in StandalonePanelNativeSmokeTest.

// ── Test-classes artifact for the native test runner ────────────────────────
// examples/tao-native-test compiles the stage-1/stage-2 suites into a GraalVM
// native image; it consumes the compiled test classes through this
// configuration (test source sets are not published otherwise).

// Apache-2.0 §4(a) / BSD-3-Clause: this JAR ships libnucleus_tao (which statically links the
// vendored tao and AccessKit forks) and, on Windows, the ANGLE DLLs — so the attribution notices
// and license texts must travel with it. Copied from the repo root so there is a single copy to
// maintain — see THIRD_PARTY_NOTICES.md §2–4.
tasks.named<Jar>("jar") {
    metaInf {
        from(rootProject.file("THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("licenses")) {
            into("licenses")
        }
    }
}

val taoTestClassesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("test-classes")
    from(sourceSets.test.get().output)
}

// Consumers get the compiled test classes *and* what those classes need at run
// time. Without the `extendsFrom`, every dependency of the test source set has
// to be repeated in each consumer, and one that is not simply throws
// NoClassDefFoundError the first time the suite reaches the code that uses it —
// which is how `examples/tao-native-test` lost Material 3 and took the whole
// GraalVM job down with the Tao main thread.
val taoTestArtifacts: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.testImplementation.get())
}

artifacts {
    add(taoTestArtifacts.name, taoTestClassesJar)
}

// ── Stage-2 headful window test suite ───────────────────────────────────────
// Real Tao windows, one process, one event loop (see
// src/test/.../headful/TaoWindowTestHarness.kt). JavaExec instead of a Test
// task because the Tao loop runs once per process and, on macOS, AppKit only
// accepts window creation from thread 0. Not part of `check`: needs a display
// (real session on macOS/Windows CI runners, Xvfb+WM on Linux).

val taoHeadfulKoverReport =
    layout.buildDirectory.file("kover/bin-reports/taoHeadful.ic")

val taoHeadfulTest by tasks.registering(JavaExec::class) {
    description = "Runs the stage-2 real-window Tao test suite (requires a display)"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nucleusframework.window.tao.headful.TaoHeadfulTestSuiteMain")
    // Unattended: a fatal must fail the suite loudly, not block in the #622
    // native dialog until the global watchdog halts and eats the real result.
    systemProperty("nucleus.tao.fatalErrorDialog", "false")
    // Same Kover JVM agent the `test` task uses, so headful window coverage
    // is counted. JavaExec is otherwise invisible to Kover.
    dependsOn(tasks.named("koverFindJar"))
    // Resolve these as RegularFileProperty at configuration time so the
    // doFirst action does not capture the Gradle script `layout` object
    // (configuration-cache incompatible).
    val koverAgentJar =
        layout.buildDirectory
            .file(libs.versions.kover.map { "kover/kover-jvm-agent-$it.jar" })
    val koverArgsFile =
        layout.buildDirectory
            .file("tmp/taoHeadful/kover-agent.args")
    val koverReportFile = taoHeadfulKoverReport
    doFirst {
        val agent = koverAgentJar.get().asFile
        val report = koverReportFile.get().asFile
        report.parentFile.mkdirs()
        val argsFile = koverArgsFile.get().asFile
        argsFile.parentFile.mkdirs()
        argsFile.writeText(
            buildString {
                appendLine("report.file=${report.absolutePath}")
                appendLine("exclude=android.*")
                appendLine("exclude=com.android.*")
                appendLine("exclude=jdk.internal.*")
            },
        )
        jvmArgs("-javaagent:${agent.absolutePath}=file:${argsFile.absolutePath}")
    }
    // Forward the watchdog / case-name filter overrides into the forked JVM.
    System.getProperty("nucleus.tao.headful.watchdogMillis")?.let {
        systemProperty("nucleus.tao.headful.watchdogMillis", it)
    }
    System.getProperty("nucleus.tao.headful.filter")?.let {
        systemProperty("nucleus.tao.headful.filter", it)
    }
    // Replays a red monkey run: the case prints the seed it used.
    System.getProperty("nucleus.tao.headful.monkeySeed")?.let {
        systemProperty("nucleus.tao.headful.monkeySeed", it)
    }
    // Replays a journal instead of a random walk (comma-separated action names).
    System.getProperty("nucleus.tao.headful.monkeyScript")?.let {
        systemProperty("nucleus.tao.headful.monkeyScript", it)
    }
    System.getProperties().stringPropertyNames().filter { it.startsWith("nucleus.dialog.appearance.") }.forEach {
        systemProperty(it, System.getProperty(it))
    }
    System.getProperty("nucleus.issue576.samples")?.let {
        systemProperty("nucleus.issue576.samples", it)
    }
    // Honor a caller-forced Linux renderer (x11 / wayland) so portal parenting
    // e2es can be launched against XWayland from a native Wayland session.
    providers.environmentVariable("NUCLEUS_TAO_LINUX_RENDERER").orNull?.let {
        environment("NUCLEUS_TAO_LINUX_RENDERER", it)
    }
    providers.environmentVariable("GDK_BACKEND").orNull?.let {
        environment("GDK_BACKEND", it)
    }
    // NO -XstartOnFirstThread here: taoApplication marshals to the AppKit main
    // thread itself (main_thread_dispatch.m), exactly like a normal `java`
    // launch — and the flag would deadlock the AWT classes the Compose host
    // touches. smokeStandalonePanelMac needs it only because it creates an
    // NSPanel directly, without the Tao loop machinery.
}

// X11 / XWayland portal parenting e2e: forces GDK onto X11 so Tao windows get
// a real XID, then parents a session xdg-desktop-portal FileChooser with
// `x11:<hex>`. Safe to run on a Wayland host (XWayland). Not part of `check`.
val taoX11PortalE2E by tasks.registering(JavaExec::class) {
    description = "E2E: X11 XID parents a real XDG portal FileChooser (forces XWayland)"
    group = "verification"
    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) }
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nucleusframework.window.tao.headful.TaoHeadfulTestSuiteMain")
    systemProperty("nucleus.tao.headful.filter", "x11 XID")
    // Unattended — see taoHeadfulTest.
    systemProperty("nucleus.tao.fatalErrorDialog", "false")
    System.getProperty("nucleus.tao.headful.watchdogMillis")?.let {
        systemProperty("nucleus.tao.headful.watchdogMillis", it)
    }
    environment("NUCLEUS_TAO_LINUX_RENDERER", "x11")
}

val smokeStandalonePanelMac by tasks.registering(JavaExec::class) {
    description = "Smoke-checks the macOS standalone-popup native chain (ownerless NSPanel + Metal)"
    group = "verification"
    onlyIf { Os.isFamily(Os.FAMILY_MAC) }
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nucleusframework.window.tao.StandalonePanelMacSmokeMain")
    // Unattended — see taoHeadfulTest.
    systemProperty("nucleus.tao.fatalErrorDialog", "false")
    // Run main() on thread 0 (the macOS main thread). The JVM normally runs
    // main() on a spawned pthread, but AppKit only permits NSWindow/NSPanel
    // creation on the true main thread. -XstartOnFirstThread is the same flag
    // LWJGL/GLFW use on macOS.
    jvmArgs("-XstartOnFirstThread")
}

// Manual smoke for #416: transparent DecoratedWindow + opaque marker over desktop.
// Captures under build/reports/tao-transparent-smoke and pixel-checks that the
// empty client composites the desktop. Not part of `check`.
//
// macOS/X11: AWT Robot. Windows: Robot omits layered windows — point
// `-Dnucleus.tao.transparent.smoke.captureTool=` at a CAPTUREBLT helper
// (build/tmp-smoke/capture_region.exe).
val taoTransparentSmoke by tasks.registering(JavaExec::class) {
    description = "Manual smoke: DecoratedWindow(transparent=true) over the desktop (#416)"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nucleusframework.window.tao.headful.TransparentWindowSmokeMain")
    // Unattended — see taoHeadfulTest.
    systemProperty("nucleus.tao.fatalErrorDialog", "false")
    // Linux: pin the window to XWayland. Robot goes through the X server, so on
    // a native Wayland session it cannot see the Tao surface (both captures come
    // back byte-identical) and xdg-shell drops setOuterPosition, leaving the
    // capture rect pointing at wherever the compositor did *not* put the window.
    // Under XWayland both work. Overridable — the smoke then refuses to emit a
    // pixel verdict on Wayland (see TransparentWindowSmokeMain).
    if (Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC)) {
        environment(
            "NUCLEUS_TAO_LINUX_RENDERER",
            providers.environmentVariable("NUCLEUS_TAO_LINUX_RENDERER").getOrElse("x11"),
        )
    }
    val outDir =
        layout.buildDirectory
            .dir("reports/tao-transparent-smoke")
            .get()
            .asFile
    systemProperty("nucleus.tao.transparent.smoke.outdir", outDir.absolutePath)
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        val captureTool =
            layout.buildDirectory
                .file("tmp-smoke/capture_region.exe")
                .get()
                .asFile
        systemProperty("nucleus.tao.transparent.smoke.captureTool", captureTool.absolutePath)
        doFirst {
            if (!captureTool.isFile) {
                error(
                    "CAPTUREBLT helper missing at ${captureTool.absolutePath}. " +
                        "Build it once with cl against capture_region.c " +
                        "(see TransparentWindowSmokeMain).",
                )
            }
        }
    }
    // Forward hold duration so a manual look is possible, e.g.
    // -Dnucleus.tao.transparent.smoke.holdMs=10000
    System.getProperty("nucleus.tao.transparent.smoke.holdMs")?.let {
        systemProperty("nucleus.tao.transparent.smoke.holdMs", it)
    }
}

// Manual smoke for #622: fatal-exception path end to end — SEVERE log, native
// error dialog, exit code 1. The expected outcome is Gradle failing with
// "finished with non-zero exit value 1" after the dialog is dismissed.
// Not part of `check`.
val taoFatalDialogSmoke by tasks.registering(JavaExec::class) {
    description = "Manual smoke: fatal-error path — native dialog then exit code 1 (#622)"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nucleusframework.window.tao.headful.FatalErrorDialogSmokeMain")
    // Forward the crash delay so the window can be looked at first, e.g.
    // -Dnucleus.tao.fatal.smoke.crashAfterMs=10000
    System.getProperty("nucleus.tao.fatal.smoke.crashAfterMs")?.let {
        systemProperty("nucleus.tao.fatal.smoke.crashAfterMs", it)
    }
    // Forward the #622 escape hatch so the smoke can also exercise the
    // dialog-less unattended path: -Dnucleus.tao.fatalErrorDialog=false
    System.getProperty("nucleus.tao.fatalErrorDialog")?.let {
        systemProperty("nucleus.tao.fatalErrorDialog", it)
    }
}

// ── Maven publication ──────────────────────────────────────────────────────

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.decorated-window-tao", publishVersion)

    pom {
        name.set("Nucleus Decorated Window Tao")
        description.set(
            "Experimental no-AWT decorated window backend for Compose Desktop, " +
                "powered by Tao via direct JNI for macOS, Windows, and Linux.",
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
