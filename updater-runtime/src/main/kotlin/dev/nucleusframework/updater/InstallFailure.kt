package dev.nucleusframework.updater

/**
 * An update install that ran and failed, detected at the next startup.
 * Returned by [NucleusUpdater.consumeInstallFailure].
 */
public data class InstallFailure(
    /** The version the install tried to reach, or `null` when it was not known. */
    val version: String?,
    /** The installer's exit status (for `pkexec`, 126 means the authentication was dismissed). */
    val exitCode: Int,
    /** The end of the installer's output, where its error message is. Empty when none was kept. */
    val output: String,
)
