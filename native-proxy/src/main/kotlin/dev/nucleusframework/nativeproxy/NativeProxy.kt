package dev.nucleusframework.nativeproxy

import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "NativeProxy"
private const val MAX_PAC_CACHE_ENTRIES = 256

/**
 * Entry point for the OS proxy configuration.
 *
 * ```kotlin
 * NativeProxy.install()                       // route every JDK connection through the OS proxy
 * val proxies = NativeProxy.proxiesFor(URI("https://intranet.corp"))
 * NativeProxy.addChangeListener { println("proxy configuration changed: $it") }
 * ```
 *
 * Windows (WinHTTP/WPAD/PAC + Internet Settings registry watching), macOS
 * (`SCDynamicStore` + PAC via `CFNetworkExecuteProxyAutoConfigurationURL`) and
 * Linux (GSettings / KDE kioslaverc / env vars) are implemented. Linux does not
 * evaluate PAC scripts yet — only static rules and bypass lists.
 */
object NativeProxy {
    private val provider = SystemProxyProvider.forCurrentPlatform()
    private val cachedSettings = AtomicReference<SystemProxySettings?>()
    private val pacResults = ConcurrentHashMap<String, List<ProxyServer>>()
    private val listeners = CopyOnWriteArrayList<(SystemProxySettings) -> Unit>()
    private val installedSelector = AtomicReference<ProxySelector?>()
    private val previousSelector = AtomicReference<ProxySelector?>()
    private val installed = AtomicBoolean(false)
    private val watcher by lazy { ProxyChangeWatcher(provider, ::onConfigurationChanged) }

    /** Whether the current platform has a native proxy backend available. */
    val isSupported: Boolean get() = provider.isSupported

    /** A selector answering purely from the OS configuration, without any fallback. */
    val selector: ProxySelector by lazy { NativeProxySelector(fallback = null) }

    /** The cached OS proxy configuration, read on first access. */
    fun settings(): SystemProxySettings = cachedSettings.get() ?: refresh()

    /** Re-reads the OS proxy configuration, dropping the PAC result cache. */
    fun refresh(): SystemProxySettings {
        pacResults.clear()
        val settings = provider.readSettings()
        cachedSettings.set(settings)
        return settings
    }

    /**
     * The proxies to try for [uri], in order. An empty list means a direct
     * connection — either because no proxy is configured or because [uri]
     * matches the bypass list.
     *
     * Resolution order matches Chromium: bypass list, then the PAC script
     * (explicit URL or WPAD), then the static proxy rules — the latter also
     * acting as the fallback when the PAC script cannot be evaluated.
     */
    fun proxiesFor(uri: URI): List<ProxyServer> {
        val settings = settings()
        if (settings.isDirect) return emptyList()
        if (settings.bypassRules.matches(uri)) return emptyList()

        if (settings.usesPacScript) {
            val resolved = resolveWithPacScript(uri, settings)
            if (resolved != null) return resolved
        }

        return settings.rules.proxiesForUrlScheme(uri.scheme.orEmpty())
    }

    /** [proxiesFor] as JDK proxies, never empty: a direct connection is [Proxy.NO_PROXY]. */
    fun javaProxiesFor(uri: URI): List<Proxy> =
        proxiesFor(uri)
            .map { it.toJavaProxy() }
            .ifEmpty { listOf(Proxy.NO_PROXY) }

    /**
     * Installs the OS proxy configuration as the JVM-wide default [ProxySelector].
     *
     * The selector that was default beforehand becomes the fallback, so JDK
     * proxy system properties keep working for URIs the OS has no opinion on.
     * Also starts watching the configuration so the selector stays in sync.
     *
     * @return false when the platform has no native backend, leaving the JVM default untouched.
     */
    fun install(): Boolean {
        if (!provider.isSupported) {
            debugln(TAG) { "No native proxy backend on this platform, keeping the JVM default selector" }
            return false
        }
        if (!installed.compareAndSet(false, true)) return true

        val previous = ProxySelector.getDefault()
        previousSelector.set(previous)
        val selector = NativeProxySelector(previous)
        installedSelector.set(selector)
        ProxySelector.setDefault(selector)
        watcher.start()
        debugln(TAG) { "Installed the native proxy selector as the JVM default" }
        return true
    }

    /** Restores the [ProxySelector] that was default before [install]. */
    fun uninstall() {
        if (!installed.compareAndSet(true, false)) return
        // Only restore when nothing else replaced the default in the meantime.
        if (ProxySelector.getDefault() === installedSelector.getAndSet(null)) {
            ProxySelector.setDefault(previousSelector.getAndSet(null))
        }
        if (listeners.isEmpty()) watcher.stop()
        debugln(TAG) { "Uninstalled the native proxy selector" }
    }

    /**
     * Registers [listener], invoked on a background thread whenever the OS proxy
     * configuration changes. Starts the configuration watcher on first listener.
     */
    fun addChangeListener(listener: (SystemProxySettings) -> Unit) {
        listeners += listener
        watcher.start()
    }

    /** Unregisters [listener], stopping the watcher when no listener is left. */
    fun removeChangeListener(listener: (SystemProxySettings) -> Unit) {
        listeners -= listener
        if (listeners.isEmpty() && !installed.get()) watcher.stop()
    }

    private fun resolveWithPacScript(
        uri: URI,
        settings: SystemProxySettings,
    ): List<ProxyServer>? {
        val key = pacCacheKey(uri)
        pacResults[key]?.let { return it }

        val resolved = provider.resolveWithPacScript(uri, settings) ?: return null
        if (pacResults.size >= MAX_PAC_CACHE_ENTRIES) pacResults.clear()
        pacResults[key] = resolved
        return resolved
    }

    /**
     * PAC results are cached per origin rather than per URL: scripts keying on
     * the path are vanishingly rare, and each miss is a blocking WinHTTP call.
     */
    private fun pacCacheKey(uri: URI): String = "${uri.scheme}://${uri.host}:${uri.port}"

    private fun onConfigurationChanged() {
        val previous = cachedSettings.get()
        val current = refresh()
        if (current == previous) return

        listeners.forEach { listener ->
            @Suppress("TooGenericExceptionCaught")
            try {
                listener(current)
            } catch (e: Exception) {
                errorln(TAG) { "Proxy change listener failed: ${e.message}" }
            }
        }
    }
}
