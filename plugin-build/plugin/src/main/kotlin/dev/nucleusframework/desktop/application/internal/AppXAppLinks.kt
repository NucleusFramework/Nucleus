package dev.nucleusframework.desktop.application.internal

import org.gradle.api.GradleException

/**
 * Turns `nativeDistributions { appLinks(…) }` into the AppX `windows.appUriHandler` extension: the
 * hosts whose `https://` links Windows hands to the app once each host's
 * `/.well-known/windows-app-web-link` names the package family.
 */
internal object AppXAppLinks {
    private const val UAP3 = "http://schemas.microsoft.com/appx/manifest/uap/windows10/3"
    private const val UAP5 = "http://schemas.microsoft.com/appx/manifest/uap/windows10/5"

    // A DNS name, optionally behind one leading `*.` wildcard (what AppUriHandler accepts).
    private val HOST = Regex("""^(\*\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$""")

    /** The hosts, lowercased and deduplicated; fails on anything that is not a bare host name. */
    fun hosts(declared: List<String>): List<String> =
        declared
            .map { it.trim().lowercase() }
            .onEach { host ->
                if (!HOST.matches(host)) {
                    throw GradleException(
                        "appLinks: \"$host\" is not a host name. Declare the bare host (\"example.com\", " +
                            "\"*.example.com\"), without scheme, port or path.",
                    )
                }
            }.distinct()

    /**
     * The `<Extensions>` entry. Its namespaces are declared on the element itself, so it works with
     * the stock electron-builder template, the widgets one and any user template alike. A wildcard
     * host needs `uap5:Host` (Windows 10 1709); `uap3:Host` only takes exact names.
     */
    fun extensionsXml(hosts: List<String>): String =
        buildString {
            appendLine()
            appendLine(
                "        <uap3:Extension xmlns:uap3=\"$UAP3\" xmlns:uap5=\"$UAP5\" Category=\"windows.appUriHandler\">",
            )
            appendLine("          <uap3:AppUriHandler>")
            for (host in hosts) {
                val prefix = if (host.startsWith("*.")) "uap5" else "uap3"
                appendLine("            <$prefix:Host Name=\"$host\" />")
            }
            appendLine("          </uap3:AppUriHandler>")
            appendLine("        </uap3:Extension>")
        }
}
