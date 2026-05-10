package dev.nucleusframework.nucleus.autolaunch

/** Internal SPI implemented by each platform/packaging backend. */
internal interface AutoLaunchBackend {
    fun state(): AutoLaunchState

    fun enable(): AutoLaunchResult

    fun disable(): AutoLaunchResult

    /** Opens the platform-native UI for managing startup apps, if available. */
    fun openSystemSettings(): Boolean = false

    /**
     * Detects whether this process was launched by the auto-launch mechanism.
     *
     * Default implementation checks for the CLI marker configured via
     * [AutoLaunchConfig.autostartArgument]. Backends that rely on out-of-band
     * signals (e.g. MSIX activation) override this.
     */
    fun wasStartedAtLogin(args: Array<String>): Boolean = containsAutostartMarker(args)

    /** Backend-specific diagnostic lines shown in [AutoLaunch.diagnostic]. */
    fun diagnosticSummary(): String = ""
}

/** Shared marker-based detection used by Win32, macOS user-dir, and any plist-driven backend. */
internal fun containsAutostartMarker(args: Array<String>): Boolean {
    val marker = AutoLaunchConfig.autostartArgument?.takeIf { it.isNotBlank() } ?: return false
    return args.any { it == marker }
}

internal object NoOpAutoLaunchBackend : AutoLaunchBackend {
    override fun state(): AutoLaunchState = AutoLaunchState.UNSUPPORTED

    override fun enable(): AutoLaunchResult = AutoLaunchResult.UNSUPPORTED

    override fun disable(): AutoLaunchResult = AutoLaunchResult.UNSUPPORTED

    override fun wasStartedAtLogin(args: Array<String>): Boolean = false
}
