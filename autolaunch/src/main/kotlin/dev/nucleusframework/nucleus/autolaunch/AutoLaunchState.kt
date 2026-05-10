package dev.nucleusframework.nucleus.autolaunch

/**
 * The current state of auto-launch at user login for this application.
 *
 * Unified model across MSIX packaged apps (Windows.ApplicationModel.StartupTask)
 * and Win32 classic apps (HKCU\...\Run + StartupApproved\Run).
 */
public enum class AutoLaunchState {
    /** Auto-launch is active; the app will start at next user logon. */
    ENABLED,

    /** Auto-launch is not configured or has been disabled programmatically. */
    DISABLED,

    /**
     * The user explicitly disabled auto-launch (Task Manager → Startup,
     * Settings → Apps → Startup, or MSIX StartupTaskState.DisabledByUser).
     *
     * This state is **final** until the user re-enables it manually.
     * Do NOT attempt to re-enable programmatically — on MSIX it will no-op,
     * and on Win32 it disrespects an explicit user choice.
     */
    DISABLED_BY_USER,

    /** Auto-launch blocked by Group Policy (MSIX only). Read-only. */
    DISABLED_BY_POLICY,

    /** Auto-launch forced on by Group Policy (MSIX only). Read-only. */
    ENABLED_BY_POLICY,

    /** Platform does not support auto-launch (e.g. macOS/Linux in current version). */
    UNSUPPORTED,
}

/** Result of an enable/disable request. */
public enum class AutoLaunchResult {
    /** Operation succeeded and state changed. */
    OK,

    /** State was already in the requested value; no action taken. */
    UNCHANGED,

    /** User explicitly disabled via system UI — cannot be re-enabled programmatically. */
    BLOCKED_BY_USER,

    /** Group Policy blocks the requested change. */
    BLOCKED_BY_POLICY,

    /** Platform does not support auto-launch. */
    UNSUPPORTED,

    /** Operation failed (native call error, I/O, etc.). */
    ERROR,
}
