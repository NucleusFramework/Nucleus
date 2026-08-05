package dev.nucleusframework.nativeproxy.linux

import dev.nucleusframework.nativeproxy.ProxyBypassRules
import dev.nucleusframework.nativeproxy.ProxyRules
import dev.nucleusframework.nativeproxy.ProxyServer
import dev.nucleusframework.nativeproxy.SystemProxyProvider
import dev.nucleusframework.nativeproxy.SystemProxySettings
import dev.nucleusframework.nativeproxy.debugln
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LinuxSystemProxyProvider"

/** How often [awaitConfigurationChange] re-reads the desktop configuration. */
private const val POLL_INTERVAL_MILLIS = 250L

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Linux backend modelled on Chromium's `ProxyConfigServiceLinux`.
 *
 * Resolution order:
 * 1. Desktop settings — `kioslaverc` on KDE, GSettings on every other session
 *    that ships `org.gnome.system.proxy` (GNOME, Cinnamon, …)
 * 2. Environment variables (`http_proxy`, `no_proxy`, …) when the desktop
 *    exposes no configuration
 *
 * PAC / WPAD evaluation is not implemented: there is no WinHTTP equivalent on
 * Linux without embedding a JS engine. When a PAC URL or WPAD is configured,
 * [resolveWithPacScript] returns `null` so the caller falls back to the static
 * rules (usually empty → direct).
 *
 * Change watching polls the configuration rather than parking on GSettings
 * signals: D-Bus deliveries for `GSettings` are bound to the process-default
 * `GMainContext`, which a JVM process does not drive. Polling is cheap (a few
 * GSettings reads per second) and works for GSettings, KDE and env alike.
 *
 * Always [isSupported]: env vars and KDE need no JNI, and GSettings is optional.
 */
internal object LinuxSystemProxyProvider : SystemProxyProvider {
    override val isSupported: Boolean = true

    private val wakeRequested = AtomicBoolean(false)

    override fun readSettings(): SystemProxySettings {
        val settings =
            if (isKdeDesktop()) {
                KdeProxySettings.read() ?: EnvProxySettings.read()
            } else {
                readGsettings() ?: EnvProxySettings.read()
            } ?: SystemProxySettings.DIRECT

        debugln(TAG) { "Linux proxy configuration: $settings" }
        return settings
    }

    override fun resolveWithPacScript(
        uri: URI,
        settings: SystemProxySettings,
    ): List<ProxyServer>? {
        if (!settings.usesPacScript) return null
        debugln(TAG) {
            "PAC/WPAD is configured but not evaluated on Linux " +
                "(pacUrl=${settings.pacUrl}, autoDetect=${settings.autoDetect}); " +
                "falling back to static rules"
        }
        return null
    }

    override fun awaitConfigurationChange(timeoutMillis: Int): Boolean {
        wakeRequested.set(false)
        val initial = readSettings()
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * NANOS_PER_MILLI

        while (System.nanoTime() < deadline) {
            if (wakeRequested.get()) return false
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (wakeRequested.get()) return false
            if (readSettings() != initial) return true
        }
        return false
    }

    override fun wakeConfigurationWatcher() {
        wakeRequested.set(true)
        // Best-effort: also poke the native side if a legacy wait is parked there.
        if (LinuxProxyBridge.isLoaded) {
            LinuxProxyBridge.wakeWatcher()
        }
    }

    private fun readGsettings(): SystemProxySettings? {
        val config = LinuxProxyBridge.getProxyConfig() ?: return null

        return SystemProxySettings(
            autoDetect = config.getOrNull(LinuxProxyBridge.INDEX_AUTO_DETECT) == "1",
            pacUrl = config.getOrNull(LinuxProxyBridge.INDEX_PAC_URL)?.takeIf { it.isNotBlank() },
            rules =
                config
                    .getOrNull(LinuxProxyBridge.INDEX_PROXY)
                    ?.let(ProxyRules::parse) ?: ProxyRules.EMPTY,
            bypassRules =
                config
                    .getOrNull(LinuxProxyBridge.INDEX_BYPASS)
                    ?.let(ProxyBypassRules::parse) ?: ProxyBypassRules.EMPTY,
        )
    }

    internal fun isKdeDesktop(getenv: (String) -> String? = System::getenv): Boolean {
        val tokens =
            sequenceOf(
                getenv("XDG_CURRENT_DESKTOP"),
                getenv("DESKTOP_SESSION"),
                getenv("GDMSESSION"),
            ).filterNotNull()
                .flatMap { it.split(':', ';', ',') }
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
        return tokens.any { it in KDE_TOKENS }
    }

    private val KDE_TOKENS =
        setOf("kde", "kde3", "kde4", "kde5", "kde6", "plasma", "plasma5", "plasma6")
}
