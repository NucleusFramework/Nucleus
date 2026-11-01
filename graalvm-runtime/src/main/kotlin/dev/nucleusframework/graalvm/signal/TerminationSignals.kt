package dev.nucleusframework.graalvm.signal

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import kotlin.system.exitProcess

/**
 * SIGINT / SIGTERM / SIGHUP for a macOS native image compiled without Substrate's signal handling
 * — the sandboxed (Mac App Store) image, see [dev.nucleusframework.graalvm.GraalVmInitializer].
 *
 * Without it those signals kill the process outright and the shutdown hooks never run. The
 * native side receives them through libdispatch (no semaphore, so the App Sandbox allows it) and
 * calls [onTerminationSignal] on a dispatch thread, as HotSpot runs them on its signal thread.
 * Shares the `nucleus_locale` library, already loaded at start-up on macOS.
 */
internal object TerminationSignals {
    private const val LIBRARY_NAME = "nucleus_locale"

    /** Exit status of a process ended by a signal, as HotSpot reports it. */
    private const val SIGNAL_EXIT_BASE = 128

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, TerminationSignals::class.java)

    /** Routes the three signals to the shutdown sequence; `false` when the bridge is unavailable. */
    fun install(): Boolean = loaded && runCatching { nativeInstall() }.getOrDefault(false)

    @JvmStatic
    private external fun nativeInstall(): Boolean

    /** Called from native code; never returns. */
    @JvmStatic
    fun onTerminationSignal(signal: Int) {
        val status = SIGNAL_EXIT_BASE + signal
        try {
            exitProcess(status)
        } catch (_: Throwable) {
            Runtime.getRuntime().halt(status)
        }
    }
}
