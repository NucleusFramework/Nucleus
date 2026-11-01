package dev.nucleusframework.graalvm

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.graalvm.encoding.PlatformEncodingInitializer
import dev.nucleusframework.graalvm.locale.NativeLocaleBridge
import dev.nucleusframework.graalvm.signal.TerminationSignals
import dev.nucleusframework.hidpi.applyLinuxHiDpiScale
import dev.nucleusframework.hidpi.capLinuxMallocArenas
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.logging.Logger

public object GraalVmInitializer {
    public val isNativeImage: Boolean =
        System.getProperty("org.graalvm.nativeimage.imagecode") != null

    /** Call once at the very start of main(), before any AWT/Compose usage. */
    public fun initialize() {
        if (isNativeImage) {
            // Initialize the JDK platform (JNU) encoding FIRST, before any AWT/font native library
            // loads. Oracle GraalVM's SVM — unlike BellSoft Liberica NIK — never calls the JDK's
            // InitializeEncoding, so libawt's JNI_OnLoad would abort the VM with "platform encoding
            // not initialized" / "Could not allocate library name". Ported from Liberica's
            // JNIPlatformNativeLibrarySupport. Harmless under Liberica (encoding already set).
            // macOS-only in effect: initialize() is gated by a native-image build-time platform
            // fold (Platform.includedIn(DARWIN)), so the `nucleus_init_platform_encoding` C shim —
            // which the plugin compiles into the image only on macOS — is referenced only there.
            // On Windows/Linux SVM eliminates the call, avoiding an undefined-symbol link error.
            PlatformEncodingInitializer.initialize()

            // Metal L&F — avoids platform-specific modules unsupported in native image
            System.setProperty("swing.defaultlaf", "javax.swing.plaf.metal.MetalLookAndFeel")

            // Resolve the executable directory
            val execDir = resolveExecDir()
            System.setProperty("java.home", execDir)
            // macOS .app bundles ship the font config in Contents/Resources/ rather than next to
            // java.home (Contents/MacOS/lib/), so tell the JDK where it actually is.
            if (Platform.Current == Platform.MacOS && System.getProperty("sun.awt.fontconfig").isNullOrBlank()) {
                resolveMacOsFontConfig(File(execDir))?.let { fontConfig ->
                    System.setProperty("sun.awt.fontconfig", fontConfig.absolutePath)
                }
            }
            // Match JVM `run` / jpackage: sidecars from appResourcesRootDir live next to the exe.
            if (System.getProperty("compose.application.resources.dir").isNullOrBlank()) {
                System.setProperty("compose.application.resources.dir", execDir)
            }

            // java.library.path → execDir + execDir/bin
            // Must be set BEFORE any System.loadLibrary() call (including HiDPI JNI below).
            // Also flush the ClassLoader cache so System.loadLibrary() picks up the new paths.
            val sep = File.pathSeparator
            System.setProperty("java.library.path", "$execDir$sep$execDir${File.separator}bin")
            resetLibraryPathCache()

            applyMacOsAppSandbox(execDir)

            // Early charset init
            Charset.defaultCharset()

            // macOS: recover the OS UI language. SubstrateVM never runs HotSpot's
            // java_props_macosx.c, so the default locale stays the POSIX "C"
            // locale (en) regardless of System Settings. Compose Resources is
            // keyed off Locale.getDefault(), so every translation falls back to
            // the default. We restore JBR behaviour by reading CoreFoundation.
            applyMacOsLocale()
        }

        // Linux: cap glibc malloc arenas (#757) before the app, Skia and driver threads exist —
        // a thread keeps the arena it got. Same JNI library as the HiDPI detection below, so it
        // too must come after java.library.path is configured.
        capLinuxMallocArenas()

        // Linux HiDPI — must come AFTER java.library.path is configured above,
        // because applyLinuxHiDpiScale() triggers LinuxStartupBridge JNI loading.
        // Sets GDK_SCALE via setenv (triggers JDK's native scaling for both
        // rendering AND mouse events) + sun.java2d.uiScale as fallback.
        applyLinuxHiDpiScale()

        if (isNativeImage) {
            try {
                System.loadLibrary("fontmanager")
            } catch (_: Throwable) {
                // Ignore — fontmanager may already be loaded or unavailable
            }
        }
    }

    /**
     * Fit a native image to the macOS App Sandbox, which only lets the app write in its container
     * and refuses native code extracted at run time (quarantined, then blocked by Gatekeeper).
     *
     * The sandboxed (App Store) bundle the plugin builds is recognized by the redirect manifest in
     * `Contents/Resources`: its third-party native libraries were extracted at build time into
     * `Contents/Frameworks`, so they join `java.library.path`, the extract-and-load shim
     * (`NucleusSandboxLoader`) is pointed at the manifest, and JNA loads `jnidispatch` from there
     * instead of unpacking it — the same properties the sandboxed JVM launcher passes.
     *
     * That image is also compiled without Substrate's signal handling (its named semaphore is denied),
     * so [TerminationSignals] gives SIGINT / SIGTERM / SIGHUP their shutdown hooks back.
     *
     * Inside the sandbox `user.home` follows `HOME`, the container: SubstrateVM reads it from the
     * password database (`/Users/<name>`) where HotSpot follows `HOME`, and every `~/Library/…`
     * path would land outside the container and be denied.
     *
     * Nothing changes for any other bundle or outside the sandbox; an explicit setting wins.
     */
    private fun applyMacOsAppSandbox(execDir: String) {
        if (Platform.Current != Platform.MacOS) return
        val layout = macOsSandboxedLayout(File(execDir))
        if (layout != null) {
            System.setProperty(
                "java.library.path",
                System.getProperty("java.library.path") + File.pathSeparator + layout.librariesDir.absolutePath,
            )
            resetLibraryPathCache()
            layout.properties.forEach(::setIfAbsent)
            // This image is compiled without Substrate's signal handling: route SIGINT / SIGTERM /
            // SIGHUP to the shutdown hooks ourselves.
            if (!TerminationSignals.install()) {
                Logger.getLogger(GraalVmInitializer::class.java.name).warning(
                    "Termination signals unavailable: SIGINT/SIGTERM will skip the shutdown hooks",
                )
            }
        }
        sandboxedUserHome(System::getenv, System.getProperty("user.home"))?.let {
            System.setProperty("user.home", it)
        }
    }

    /** What the sandboxed bundle layout adds to the image's environment; see [applyMacOsAppSandbox]. */
    internal class SandboxedLayout(
        val librariesDir: File,
        val properties: Map<String, String>,
    )

    /** The sandboxed layout of the bundle [execDir] (`Contents/MacOS`) belongs to, or `null` for any other. */
    internal fun macOsSandboxedLayout(execDir: File): SandboxedLayout? {
        val contents = execDir.absoluteFile.normalize().parentFile ?: return null
        val manifest = File(contents, "Resources/$SANDBOX_MANIFEST_FILE")
        if (!manifest.isFile) return null
        val frameworks = File(contents, "Frameworks")
        return SandboxedLayout(
            librariesDir = frameworks,
            properties =
                mapOf(
                    SANDBOX_MANIFEST_PROPERTY to manifest.absolutePath,
                    "jna.nounpack" to "true",
                    "jna.nosys" to "true",
                    "jna.boot.library.path" to frameworks.absolutePath,
                    "jna.library.path" to frameworks.absolutePath,
                ),
        )
    }

    /**
     * The container `HOME` to use as `user.home` when sandboxed, or `null` to leave [userHome] as is:
     * outside the sandbox, or when it is not SubstrateVM's default — the account home the container
     * lives in (`<account>/Library/Containers/<id>/Data`) — so an explicit `-Duser.home` wins.
     */
    internal fun sandboxedUserHome(
        env: (String) -> String?,
        userHome: String?,
    ): String? {
        if (env(APP_SANDBOX_CONTAINER_ID_ENV).isNullOrBlank()) return null
        val home = env("HOME")?.takeIf { it.isNotBlank() && File(it).isDirectory } ?: return null
        val accountHome = home.substringBefore(CONTAINERS_SEGMENT, missingDelimiterValue = "")
        return home.takeIf { userHome.isNullOrBlank() || userHome.trimEnd('/') == accountHome }
    }

    private fun setIfAbsent(
        key: String,
        value: String,
    ) {
        if (System.getProperty(key).isNullOrBlank()) System.setProperty(key, value)
    }

    /** Set by macOS in the environment of every sandboxed process. */
    private const val APP_SANDBOX_CONTAINER_ID_ENV = "APP_SANDBOX_CONTAINER_ID"

    /** Where a sandboxed app's container sits under the account home. */
    private const val CONTAINERS_SEGMENT = "/Library/Containers/"

    /** Written by the plugin's sandboxed pipeline (`SandboxMarkers.MANIFEST_FILENAME`). */
    private const val SANDBOX_MANIFEST_FILE = "nucleus-sandbox-manifest.properties"

    /** Read by the shim (`NucleusSandboxLoader.MANIFEST_PATH_PROPERTY`). */
    private const val SANDBOX_MANIFEST_PROPERTY = "nucleus.sandbox.manifest"

    /**
     * Align [Locale.getDefault] with the macOS UI language under native-image.
     *
     * Faithful to JBR: java_props_macosx.c resolves the default locale from
     * CoreFoundation's preferred languages first and only falls back to
     * `LANG`/`LC_*` when that yields nothing. So we read CoreFoundation
     * unconditionally and leave the env-derived locale untouched only when it
     * returns no language.
     */
    private fun applyMacOsLocale() {
        if (Platform.Current != Platform.MacOS) return
        if (!NativeLocaleBridge.isLoaded) return

        val tag =
            runCatching { NativeLocaleBridge.nativePreferredLanguageTag() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return

        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isNullOrBlank()) return

        Locale.setDefault(locale)
        // Keep user.* in sync for any code reading the properties directly.
        System.setProperty("user.language", locale.language)
        if (locale.country.isNotBlank()) {
            System.setProperty("user.country", locale.country)
        }
        if (locale.script.isNotBlank()) {
            System.setProperty("user.script", locale.script)
        }
    }

    /**
     * Locate `fontconfig.properties` inside a macOS `.app` bundle's `Contents/Resources/`.
     *
     * The file cannot be shipped under `Contents/MacOS/`: Gatekeeper treats everything below that
     * directory (subdirectories included) as nested code, and a non-Mach-O file there makes
     * `spctl -a -t exec` reject the bundle even when it is Developer ID signed, notarized and
     * stapled. `FontConfiguration` only scans `<java.home>/lib`, so the caller has to hand it the
     * `Contents/Resources/` copy through the `sun.awt.fontconfig` system property.
     *
     * It must be the text form: the JDK always parses a `sun.awt.fontconfig` file as properties,
     * so a binary `fontconfig.bfc` given that way yields an empty config and every font manager
     * construction fails with "Fontconfig head is null".
     *
     * [execDir] is `Contents/MacOS`, hence the bundle resources one level up. Returns `null` when
     * the file is absent — that covers the legacy `<execDir>/lib/fontconfig.bfc` layout, which
     * `<java.home>/lib` already resolves on its own.
     */
    internal fun resolveMacOsFontConfig(execDir: File): File? =
        // normalize(): execDir can end in "/." (e.g. `./zayit`), whose parent is MacOS/ itself.
        execDir.absoluteFile
            .normalize()
            .parentFile
            ?.resolve("Resources/fontconfig.properties")
            ?.takeIf { it.isFile }

    /**
     * Resolve the directory containing the running executable.
     * On Linux, `/proc/self/exe` gives the true absolute path even when the
     * binary is invoked via `PATH` or a symlink.
     */
    private fun resolveExecDir(): String {
        val procSelf = File("/proc/self/exe")
        if (procSelf.exists()) {
            try {
                return procSelf.canonicalFile.parentFile.absolutePath
            } catch (_: Throwable) {
                // fall through
            }
        }
        return execDirOf(
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(""),
            System.getenv("PWD"),
        )
    }

    /**
     * The directory of the executable [command] names. On macOS the command is the path the
     * process was started with, so it is canonicalized: launched through a symlink (a CLI link in
     * `/usr/local/bin`) or a relative path (`./app`), its parent would otherwise not be the bundle's
     * `Contents/MacOS`, and fonts and libraries would be looked up next to the link.
     *
     * A relative command is resolved against [workingDir] (the shell's `PWD`) when it is an absolute
     * path: inside the App Sandbox SubstrateVM's `user.dir` is not the directory the process was
     * started from.
     */
    internal fun execDirOf(
        command: String,
        workingDir: String? = null,
    ): String {
        if (command.isBlank()) return "."
        val started = File(command)
        val file =
            if (started.isAbsolute || workingDir == null || !File(workingDir).isAbsolute) {
                started
            } else {
                File(workingDir, command)
            }
        val resolved =
            try {
                file.canonicalFile
            } catch (_: Exception) {
                file.absoluteFile
            }
        return resolved.parentFile?.absolutePath ?: "."
    }

    /**
     * Flush the JVM's cached library search paths.
     *
     * `ClassLoader` caches `java.library.path` into static fields (`sys_paths`
     * and `usr_paths`) on first use and never re-reads the system property.
     * Nullifying these fields forces the next `System.loadLibrary()` call to
     * re-parse `java.library.path`.
     */
    private fun resetLibraryPathCache() {
        try {
            val classLoader = ClassLoader::class.java
            for (fieldName in arrayOf("sys_paths", "usr_paths")) {
                try {
                    val field = classLoader.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(null, null)
                } catch (_: NoSuchFieldException) {
                    // Field may not exist in this JDK/SubstrateVM version
                }
            }
        } catch (_: Throwable) {
            // Reflection may be restricted — loadLibrary will use its default paths
        }
    }
}
