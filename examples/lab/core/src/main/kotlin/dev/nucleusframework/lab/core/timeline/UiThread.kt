package dev.nucleusframework.lab.core.timeline

/**
 * The thread Compose runs on. On Tao it is the native event-loop thread, which has no
 * stable name across platforms (`main` on Windows/Linux, an attached AppKit thread on
 * macOS), so the shell captures it from its first composition instead of guessing.
 */
object UiThread {
    @Volatile
    private var thread: Thread? = null

    /** Called once from the UI thread at startup. */
    fun capture() {
        if (thread == null) thread = Thread.currentThread()
    }

    /** `null` until [capture] ran. */
    fun isCurrent(candidate: Thread = Thread.currentThread()): Boolean? = thread?.let { it === candidate }
}
