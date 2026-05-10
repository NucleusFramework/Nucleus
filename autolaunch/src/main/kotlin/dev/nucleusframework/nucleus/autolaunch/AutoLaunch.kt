package dev.nucleusframework.nucleus.autolaunch

import dev.nucleusframework.nucleus.autolaunch.linux.LinuxAutoLaunch
import dev.nucleusframework.nucleus.autolaunch.windows.NativeAutoLaunchBridge
import dev.nucleusframework.nucleus.autolaunch.windows.WindowsAutoLaunch
import dev.nucleusframework.nucleus.core.runtime.ExecutableRuntime
import dev.nucleusframework.nucleus.core.runtime.ExecutableType

private const val MAC_SMAPPSERVICE_BACKEND_CLASS =
    "dev.nucleusframework.nucleus.autolaunch.macos.MacSMAppServiceBackend"

/**
 * Cross-platform auto-launch at user login.
 *
 * Usage:
 * ```
 * when (val state = AutoLaunch.state()) {
 *     AutoLaunchState.ENABLED -> {}
 *     AutoLaunchState.DISABLED -> AutoLaunch.enable()
 *     AutoLaunchState.DISABLED_BY_USER -> AutoLaunch.openSystemSettings()
 *     else -> {}
 * }
 * ```
 *
 * Windows implementation auto-detects MSIX vs Win32 at runtime and uses
 * the appropriate API (`Windows.ApplicationModel.StartupTask` for MSIX,
 * `HKCU\...\Run` + `StartupApproved\Run` for MSI/NSIS).
 *
 * macOS builds (DMG and PKG alike) register themselves via
 * `SMAppService.mainApp` — the entry appears under **System Settings → Login
 * Items → Open at Login**. Requires macOS 13+; older releases return
 * [AutoLaunchState.UNSUPPORTED].
 *
 * Linux is no-op in this version.
 */
@Suppress("TooManyFunctions")
public object AutoLaunch {
    private val backend: AutoLaunchBackend by lazy { resolveBackend() }

    /**
     * Eagerly resolves the platform backend and loads any JNI library it requires.
     *
     * The first call into any `AutoLaunch` method triggers `System.load()` on the
     * calling thread. On macOS that first dlopen can take 100–300 ms due to AMFI
     * code-signature validation. Call [preload] from a background daemon thread
     * in `main()` to avoid blocking the UI thread later.
     */
    @JvmStatic
    public fun preload() {
        backend
    }

    /** Current auto-launch state. */
    @JvmStatic
    public fun state(): AutoLaunchState = backend.state()

    /**
     * Diagnostic string summarizing the resolved backend, the detected executable
     * type, and on Windows the underlying native JNI operations log. Safe to show
     * in debug UI; intended for troubleshooting a stuck `UNSUPPORTED` state.
     */
    @JvmStatic
    public fun diagnostic(): String =
        buildString {
            val os = System.getProperty("os.name", "")
            appendLine("backend: ${backend.javaClass.simpleName}")
            appendLine("os.name: $os")
            appendLine("executableType: ${ExecutableRuntime.type()}")
            macBackendResolutionError?.let { appendLine("macBackendError: $it") }
            append(backend.diagnosticSummary())
            if (os.lowercase().contains("win")) {
                append(NativeAutoLaunchBridge.getDiagnostic())
            }
        }

    @Volatile
    private var macBackendResolutionError: String? = null

    /** `true` if the app is configured to launch at login. */
    @JvmStatic
    public fun isEnabled(): Boolean =
        when (state()) {
            AutoLaunchState.ENABLED, AutoLaunchState.ENABLED_BY_POLICY -> true
            else -> false
        }

    /**
     * `true` if auto-launch was explicitly disabled by the user via system UI.
     * In that state, programmatic re-enable is a no-op — show the user a
     * link to [openSystemSettings] instead.
     */
    @JvmStatic
    public fun isUserLocked(): Boolean = state() == AutoLaunchState.DISABLED_BY_USER

    /**
     * Request auto-launch to be enabled.
     *
     * Returns [AutoLaunchResult.BLOCKED_BY_USER] if the user has explicitly
     * disabled via system UI — do NOT loop on this, it will never succeed
     * without manual user action.
     */
    @JvmStatic
    public fun enable(): AutoLaunchResult = backend.enable()

    /** Request auto-launch to be disabled. */
    @JvmStatic
    public fun disable(): AutoLaunchResult = backend.disable()

    /**
     * Opens the platform system settings page for managing startup apps.
     * On Windows, opens `ms-settings:startupapps`. On macOS, opens
     * System Settings → General → Login Items.
     *
     * @return `true` if the system UI was launched successfully.
     */
    @JvmStatic
    public fun openSystemSettings(): Boolean = backend.openSystemSettings()

    /**
     * Detects whether this process was started by the auto-launch mechanism.
     *
     * Detection strategy is delegated to the resolved backend:
     * - **Win32 / MSI / NSIS**: looks for the marker argument
     *   ([AutoLaunchConfig.autostartArgument], default `--nucleus-autostart`)
     *   written into the launch entry by the registry write at `enable()` time.
     * - **MSIX packaged desktop**: walks the process tree and checks whether an
     *   external ancestor is `sihost.exe` (Shell Infrastructure Host — how MSIX
     *   startup-task activations are issued). Supported since Windows 10 v1809.
     * - **macOS**: reads the `keyAELaunchedAsLogInItem` marker from the
     *   `kAEOpenApplication` AppleEvent that `loginwindow` dispatches at login.
     * - **Linux (host)**: checks the `INVOCATION_ID` env var that systemd injects
     *   for every unit invocation — equivalent to MSIX `StartupTask` activation.
     * - **Linux (Flatpak)**: looks for the CLI marker
     *   ([AutoLaunchConfig.autostartArgument]) injected into the portal's
     *   `commandline`. The XDG Desktop Portal exposes no login-launch signal to
     *   sandboxed apps — this is the only available mechanism.
     *
     * **macOS timing note**: the AppleEvent is processed by `NSApplication.run()`,
     * so the first call from `main()` (before any AWT/Compose code runs) typically
     * returns `false`. Subsequent calls — once the Compose application loop is
     * up — will return `true` if the app was login-launched. This method only
     * caches positive results, so it is safe to poll it from a `LaunchedEffect`
     * or `remember` block to learn the actual value.
     *
     * **Dev-mode short-circuit**: when [ExecutableRuntime.isDev] is `true`
     * (Gradle `:run`, IDE launches), this method returns `false` unconditionally.
     * Backends rely on env vars or AppleEvents inherited from the parent shell
     * (notably macOS's `LaunchInstanceID`, which is inherited from Terminal),
     * which would otherwise produce false positives. **Production builds must
     * configure either the `nucleus.executable.type` system property or the
     * `.nucleus-executable-type` marker file** — otherwise [ExecutableRuntime]
     * defaults to [ExecutableType.DEV] and this method will always return
     * `false`, masking real login launches.
     *
     * @param args the application's `main(args: Array<String>)` arguments
     */
    @JvmStatic
    public fun wasStartedAtLogin(args: Array<String>): Boolean {
        if (ExecutableRuntime.isDev()) return false
        if (startedAtLoginSticky) return true
        val result = backend.wasStartedAtLogin(args)
        if (result) startedAtLoginSticky = true
        return result
    }

    @Volatile
    private var startedAtLoginSticky: Boolean = false

    private fun resolveBackend(): AutoLaunchBackend {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> WindowsAutoLaunch
            os.contains("mac") -> loadMacSMAppServiceBackendOrFallback()
            os.contains("linux") -> LinuxAutoLaunch
            else -> NoOpAutoLaunchBackend
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadMacSMAppServiceBackendOrFallback(): AutoLaunchBackend =
        try {
            val klass = Class.forName(MAC_SMAPPSERVICE_BACKEND_CLASS)
            klass.getField("INSTANCE").get(null) as AutoLaunchBackend
        } catch (t: Throwable) {
            // service-management-macos not on classpath — auto-launch on macOS
            // requires that dependency. The backend itself returns UNSUPPORTED
            // on macOS < 13 via AppServiceManager.isAvailable.
            macBackendResolutionError = "${t.javaClass.simpleName}: ${t.message}"
            NoOpAutoLaunchBackend
        }
}
