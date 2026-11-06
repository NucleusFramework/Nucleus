package dev.nucleusframework.nativeproxy

private const val TAG = "ProxyChangeWatcher"
private const val WAIT_TIMEOUT_MILLIS = 60_000

/**
 * Chromium waits 2 s after a registry notification before re-reading the
 * configuration: the settings UI writes several values in a row, and reading in
 * the middle of that burst yields a torn configuration.
 */
private const val COALESCE_DELAY_MILLIS = 2_000L

/**
 * Daemon thread parking inside the platform provider until the OS proxy
 * configuration changes, then invoking [onChange] on that same thread.
 */
internal class ProxyChangeWatcher(
    private val provider: SystemProxyProvider,
    private val onChange: () -> Unit,
) {
    private val lock = Any()
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (!provider.isSupported) return
        synchronized(lock) {
            if (thread != null) return
            running = true
            thread =
                Thread(::watch, "nucleus-proxy-watcher").apply {
                    isDaemon = true
                    start()
                }
        }
    }

    fun stop() {
        val stopped: Thread?
        synchronized(lock) {
            running = false
            stopped = thread
            thread = null
        }
        if (stopped != null) {
            provider.wakeConfigurationWatcher()
        }
    }

    private fun watch() {
        debugln(TAG) { "Watching the OS proxy configuration for changes" }
        while (running) {
            if (provider.awaitConfigurationChange(WAIT_TIMEOUT_MILLIS) && running) {
                coalesceBurst()
                debugln(TAG) { "OS proxy configuration changed" }
                onChange()
            }
        }
        debugln(TAG) { "Stopped watching the OS proxy configuration" }
    }

    private fun coalesceBurst() {
        try {
            Thread.sleep(COALESCE_DELAY_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }
}
