package dev.nucleusframework.updater.provider

import dev.nucleusframework.core.runtime.Platform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.StringReader
import java.net.URI
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/** Shared by [GitHubProvider] and [PrivateGitHubProvider]. */
internal object GitHubReleases {
    const val HTTPS = "https"
    private const val HTTP = "http"
    const val DEFAULT_HOST = "github.com"
    const val DEFAULT_PROTOCOL = HTTPS
    const val LATEST_CHANNEL = "latest"

    /** How many releases `releases.atom` lists: the newest ten. */
    const val FEED_RELEASES = 10

    private const val ATOM_NS = "http://www.w3.org/2005/Atom"

    /** electron-updater's `hrefRegExp`. */
    private val TAG_HREF = Regex("""/tag/([^/]+)$""")

    private const val SEMVER_PRERELEASE = 4

    /** semver.org's SemVer 2.0 grammar. */
    private val SEMVER =
        Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
                """(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?""" +
                """(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$""",
        )

    val json: Json = Json { ignoreUnknownKeys = true }

    /** A release as GitHub answers it, reduced to its tag. */
    @Serializable
    class TaggedRelease(
        @SerialName("tag_name") val tagName: String,
    )

    fun isStable(channel: String): Boolean = channel.equals(LATEST_CHANNEL, ignoreCase = true)

    /** `latest.yml` on Windows, `<channel>-mac.yml` / `<channel>-linux.yml` elsewhere. */
    fun metadataFileName(
        channel: String,
        platform: Platform,
    ): String {
        val suffix =
            when (platform) {
                Platform.Windows -> ""
                Platform.MacOS -> "mac"
                Platform.Linux -> "linux"
                Platform.Unknown -> ""
            }
        return if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
    }

    /**
     * Whether [tag] is a SemVer 2.0 version, optionally prefixed with `v`. Feeds carry other tags
     * too (`nightly`); those are on no channel. `.` and `..` never match, so a tag cannot step out
     * of `releases/download/`.
     */
    fun isReleaseTag(tag: String): Boolean = tag != "." && tag != ".." && SEMVER.matches(tag.removePrefix("v"))

    /**
     * The first pre-release identifier of [version] (`v2.3.5-beta.8` → `beta`), or `null` for a
     * release, or for anything that is not a SemVer version. Build metadata is not a pre-release:
     * `v1.0.0+build-5` has none.
     */
    fun prereleaseChannel(version: String): String? =
        SEMVER
            .matchEntire(version.removePrefix("v"))
            ?.groups
            ?.get(SEMVER_PRERELEASE)
            ?.value
            ?.substringBefore('.')

    /**
     * The name under which a manifest's file is published as a release asset: its last path segment,
     * spaces replaced by dashes, as electron-builder uploads it.
     */
    fun assetName(fileUrl: String): String = fileUrl.substringAfterLast('/').replace(' ', '-')

    /**
     * The tag of each `<entry>` of a releases Atom feed, newest first, read from the `href` of the
     * entry's first `<link>` (`…/releases/tag/<tag>`), as electron-updater reads it. Release notes
     * are escaped text inside `<content>`, so the links they contain are never read.
     *
     * @throws javax.xml.stream.XMLStreamException when [xml] is not well-formed.
     */
    fun feedTags(xml: String): List<String> {
        val reader = feedReader(xml)
        try {
            val tags = mutableListOf<String>()
            var depth = 0
            // Depth of the open <entry>, or -1 outside one; and whether its link was read.
            var entryDepth = -1
            var linkRead = false
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        if (reader.isAtom("entry") && entryDepth < 0) {
                            entryDepth = depth
                            linkRead = false
                        } else if (reader.isAtom("link") && depth == entryDepth + 1 && !linkRead) {
                            linkRead = true
                            entryTag(reader.getAttributeValue(null, "href"))?.let(tags::add)
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        if (depth == entryDepth) entryDepth = -1
                        depth--
                    }
                }
            }
            return tags
        } finally {
            reader.close()
        }
    }

    private fun entryTag(href: String?): String? = href?.let { TAG_HREF.find(it)?.groupValues?.get(1) }

    private fun XMLStreamReader.isAtom(name: String): Boolean = localName == name && namespaceURI == ATOM_NS

    /** The JDK's own StAX parser, with no DTDs and no external entities: the feed is remote input. */
    private fun feedReader(xml: String): XMLStreamReader =
        XMLInputFactory
            .newDefaultFactory()
            .apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }.createXMLStreamReader(StringReader(xml))

    /**
     * Validates [protocol] and [host] and returns `<protocol>://<host>`. Plain http is accepted for
     * a loopback host only, as in [GenericProvider]: it would let an attacker swap the manifest and
     * the artifact together.
     */
    fun baseUrl(
        protocol: String,
        host: String,
        provider: String,
    ): String {
        val scheme = protocol.lowercase()
        require(scheme == HTTPS || scheme == HTTP) {
            "$provider protocol must be \"$HTTPS\" or \"$HTTP\" (got: $protocol)."
        }
        val uri = runCatching { URI("$scheme://$host") }.getOrNull()
        require(
            host.isNotBlank() &&
                uri?.host != null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawUserInfo == null,
        ) {
            "$provider host must be a bare host name such as \"github.example.com\" " +
                "(optionally with a port), without a scheme or path (got: $host)."
        }
        require(scheme == HTTPS || isLoopbackHost(uri.host)) {
            "$provider allows http only for a loopback host (local testing), got: $host. Plain http would " +
                "let an on-path attacker tamper with the update manifest and the artifact together."
        }
        return "$scheme://$host"
    }
}
