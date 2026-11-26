package dev.nucleusframework.nativeproxy.linux

import dev.nucleusframework.nativeproxy.ProxyBypassRules
import dev.nucleusframework.nativeproxy.ProxyProtocol
import dev.nucleusframework.nativeproxy.ProxyRules
import dev.nucleusframework.nativeproxy.ProxyServer
import dev.nucleusframework.nativeproxy.SystemProxySettings
import dev.nucleusframework.nativeproxy.debugln
import java.io.File

private const val TAG = "KdeProxySettings"
private const val PROXY_SECTION = "[Proxy Settings]"

/** KDE `ProxyType`: no proxy. */
private const val KDE_PROXY_NONE = 0

/** KDE `ProxyType`: manual host:port list. */
private const val KDE_PROXY_MANUAL = 1

/** KDE `ProxyType`: PAC script URL. */
private const val KDE_PROXY_PAC = 2

/** KDE `ProxyType`: WPAD auto-detect. */
private const val KDE_PROXY_WPAD = 3

/** KDE `ProxyType`: manual, but host fields name environment variables. */
private const val KDE_PROXY_ENV = 4

/**
 * Reads KDE's `kioslaverc` proxy section.
 *
 * Port of Chromium's `SettingGetterImplKDE`. Looks under `$KDEHOME`,
 * `~/.config`, and `$XDG_CONFIG_DIRS` for a `kioslaverc` file. Later paths
 * override earlier ones (ascending priority, matching Chromium).
 *
 * Returns `null` when no readable `kioslaverc` with a `[Proxy Settings]`
 * section is found.
 */
internal object KdeProxySettings {
    fun read(getenv: (String) -> String? = System::getenv): SystemProxySettings? {
        val values = loadMergedSettings(getenv) ?: return null
        return settingsFromKioslaverc(values, getenv)
    }

    private fun loadMergedSettings(getenv: (String) -> String?): Map<String, String>? {
        val files = resolveKioslavercPaths(getenv)
        if (files.isEmpty()) return null

        val values = linkedMapOf<String, String>()
        var opened = false
        for (file in files.filter { it.isFile && it.canRead() }) {
            val section = readProxySection(file) ?: continue
            if (!opened) {
                values.clear()
                opened = true
            }
            values.putAll(section)
            debugln(TAG) { "Loaded ${section.size} keys from ${file.absolutePath}" }
        }
        return values.takeIf { opened && it.isNotEmpty() }
    }

    internal fun resolveKioslavercPaths(getenv: (String) -> String?): List<File> {
        val dirs = mutableListOf<File>()
        val kdeHome = getenv("KDEHOME")
        if (!kdeHome.isNullOrBlank()) {
            dirs += File(kdeHome, "share/config")
        } else {
            val home = getenv("HOME")
            // Low priority first so later putAll wins.
            val xdgDirs = getenv("XDG_CONFIG_DIRS").orEmpty()
            for (dir in xdgDirs
                .split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .reversed()) {
                dirs += File(dir)
            }
            if (!home.isNullOrBlank()) {
                dirs += File(home, ".kde/share/config")
                dirs += File(home, ".kde4/share/config")
                dirs += File(home, ".config")
            }
        }
        return dirs.map { File(it, "kioslaverc") }
    }

    internal fun readProxySection(file: File): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        try {
            file.bufferedReader().useLines { lines ->
                var inSection = false
                lines.forEach { raw ->
                    val line = raw.trimEnd('\r')
                    when {
                        line.startsWith('[') -> inSection = line.trim() == PROXY_SECTION
                        !inSection -> Unit
                        else -> parseKeyValue(line)?.let { (key, value) -> result[key] = value }
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseKeyValue(line: String): Pair<String, String>? {
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        var key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        if (key.endsWith(']')) {
            val bracket = key.lastIndexOf('[')
            if (bracket > 0) key = key.substring(0, bracket).trimEnd()
        }
        return key.takeIf { it.isNotEmpty() }?.let { it to value }
    }

    private fun settingsFromKioslaverc(
        values: Map<String, String>,
        getenv: (String) -> String?,
    ): SystemProxySettings? {
        val proxyType = values["ProxyType"]?.toIntOrNull() ?: KDE_PROXY_NONE
        return when (proxyType) {
            KDE_PROXY_NONE -> SystemProxySettings.DIRECT
            KDE_PROXY_PAC -> pacSettings(values)
            KDE_PROXY_WPAD -> SystemProxySettings(autoDetect = true)
            KDE_PROXY_MANUAL, KDE_PROXY_ENV -> manualSettings(values, getenv, proxyType == KDE_PROXY_ENV)
            else -> SystemProxySettings.DIRECT
        }
    }

    private fun pacSettings(values: Map<String, String>): SystemProxySettings {
        val script = values["Proxy Config Script"]?.takeIf { it.isNotBlank() }
        return if (script != null) {
            val pacUrl = if (script.startsWith('/')) "file://$script" else script
            SystemProxySettings(pacUrl = pacUrl)
        } else {
            SystemProxySettings(autoDetect = true)
        }
    }

    private fun manualSettings(
        values: Map<String, String>,
        getenv: (String) -> String?,
        indirect: Boolean,
    ): SystemProxySettings? {
        fun resolve(key: String): String? {
            val raw = values[key]?.takeIf { it.isNotBlank() } ?: return null
            if (!indirect) return normalizeKdeHost(raw)
            return getenv(raw)?.takeIf { it.isNotBlank() }?.let(::normalizeKdeHost)
        }

        val http = resolve("httpProxy")?.let { ProxyServer.parse(it, ProxyProtocol.HTTP) }
        val https = resolve("httpsProxy")?.let { ProxyServer.parse(it, ProxyProtocol.HTTP) }
        val ftp = resolve("ftpProxy")?.let { ProxyServer.parse(it, ProxyProtocol.HTTP) }
        val socks = resolve("socksProxy")?.let { ProxyServer.parse(it, ProxyProtocol.SOCKS5) }

        if (listOfNotNull(http, https, ftp, socks).isEmpty()) return null

        val rules = buildManualRules(http, https, ftp, socks)
        val noProxyRaw =
            if (indirect) {
                values["NoProxyFor"]?.let { getenv(it) }.orEmpty()
            } else {
                values["NoProxyFor"].orEmpty()
            }
        val bypass =
            if (noProxyRaw.isBlank()) {
                ProxyBypassRules.EMPTY
            } else {
                ProxyBypassRules
                    .parse(noProxyRaw.replace(',', ';'))
                    .withSuffixMatching()
            }

        return SystemProxySettings(rules = rules, bypassRules = bypass)
    }

    private fun buildManualRules(
        http: ProxyServer?,
        https: ProxyServer?,
        ftp: ProxyServer?,
        socks: ProxyServer?,
    ): ProxyRules =
        when {
            socks != null && http == null && https == null && ftp == null ->
                ProxyRules(singleProxies = listOf(socks))
            http != null && https == null && ftp == null && socks == null ->
                ProxyRules(singleProxies = listOf(http))
            else ->
                ProxyRules(
                    proxiesForHttp = http?.let(::listOf).orEmpty(),
                    proxiesForHttps = https?.let(::listOf).orEmpty(),
                    proxiesForFtp = ftp?.let(::listOf).orEmpty(),
                    fallbackProxies = socks?.let(::listOf).orEmpty(),
                )
        }

    /** KDE 5+ uses a space between host and port; normalise to `host:port`. */
    private fun normalizeKdeHost(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("//:")) return ""
        val space = trimmed.indexOf(' ')
        return if (space > 0) {
            trimmed.substring(0, space) + ":" + trimmed.substring(space + 1).trim()
        } else {
            trimmed
        }
    }
}
