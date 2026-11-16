package dev.nucleusframework.desktop.application.internal

import org.gradle.api.GradleException

/** The hosts of `nativeDistributions { appLinks(…) }`, shared by the AppX and macOS packaging. */
internal object AppLinkHosts {
    // A DNS name, optionally behind one leading `*.` wildcard (what both AppUriHandler and
    // Associated Domains accept).
    private val HOST = Regex("""^(\*\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$""")

    /** The hosts, lowercased and deduplicated; fails on anything that is not a bare host name. */
    fun normalize(declared: List<String>): List<String> =
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
}
