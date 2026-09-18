package dev.nucleusframework.core.runtime

import java.awt.EventQueue
import java.util.concurrent.Executor

/**
 * Single marshalling point for callbacks that must reach the host's UI thread.
 *
 * Native integrations (notifications, launchers, media keys, …) receive their
 * callbacks on an OS thread — a D-Bus signal thread, a WinRT completion
 * thread, the AppKit main thread — and must hand them to whichever thread the
 * host treats as its UI thread before touching application state.
 *
 * That thread depends on the window backend ([WindowBackend]):
 *
 *  - on [WindowBackend.Tao] it is the native Tao main thread, which Nucleus
 *    registers here via [setExecutor] when the event loop starts;
 *  - in a plain AWT / Compose Desktop / Swing host that does not go through
 *    `nucleusApplication`, nothing registers an executor and [post] falls back
 *    to the AWT event dispatch thread.
 *
 * Posting to the AWT EDT unconditionally is what issue #310 was: under Tao the
 * EDT is *not* Compose's UI thread, so callbacks either ran on the wrong thread
 * or were silently dropped.
 *
 * [post] always queues; it never runs [block] inline, even when called from the
 * UI thread itself, so callback ordering is the same on every backend.
 */
public object NucleusUiThread {
    @Volatile
    private var executor: Executor? = null

    /**
     * Registers the executor that marshals to the host's UI thread, or `null`
     * to restore the AWT EDT fallback.
     *
     * Called by Nucleus when the window backend takes over the main thread;
     * not intended for application code.
     */
    @JvmStatic
    public fun setExecutor(executor: Executor?) {
        this.executor = executor
    }

    /**
     * `true` when a backend has registered its UI-thread executor — i.e. [post]
     * marshals to the backend's thread rather than to the AWT EDT fallback.
     */
    @JvmStatic
    public val isRegistered: Boolean
        get() = executor != null

    /** Queues [block] on the host's UI thread. Safe to call from any thread. */
    @JvmStatic
    public fun post(block: () -> Unit) {
        val runnable = Runnable { block() }
        val target = executor
        if (target != null) {
            target.execute(runnable)
        } else {
            EventQueue.invokeLater(runnable)
        }
    }
}
