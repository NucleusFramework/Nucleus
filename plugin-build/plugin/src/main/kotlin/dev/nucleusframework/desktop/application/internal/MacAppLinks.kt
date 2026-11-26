package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.internal.utils.MacUtils
import org.gradle.api.logging.Logger
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turns `nativeDistributions { appLinks(…) }` into macOS Universal Links: the
 * `com.apple.developer.associated-domains` entitlement (`applinks:<host>`) on the bundle and the
 * provisioning profile that grants it in `Contents/embedded.provisionprofile`.
 *
 * Associated Domains is a restricted entitlement: AMFI refuses to launch an app that claims it
 * without a profile granting it. So it is only added to a build signed with a real identity whose
 * `macOS { provisioningProfile }` grants it; anything else keeps its entitlements and warns.
 */
internal object MacAppLinks {
    const val ASSOCIATED_DOMAINS = "com.apple.developer.associated-domains"
    private const val APPLICATION_IDENTIFIER = "com.apple.application-identifier"
    private const val TEAM_IDENTIFIER = "com.apple.developer.team-identifier"

    /** What a provisioning profile grants, read from its decoded (`security cms -D`) plist. */
    data class ProfileGrant(
        val associatedDomains: Boolean,
        val applicationIdentifier: String?,
        val teamIdentifier: String?,
    )

    fun parseProfile(decodedProfile: String): ProfileGrant {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                // The plist DOCTYPE points at apple.com; never fetch it.
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isNamespaceAware = false
            }
        val root = factory.newDocumentBuilder().parse(decodedProfile.byteInputStream()).documentElement
        val entitlements =
            root
                .childElements()
                .firstOrNull { it.tagName == "dict" }
                ?.dictEntries()
                ?.get("Entitlements")
                ?.takeIf { it.tagName == "dict" }
                ?.dictEntries()
                .orEmpty()
        return ProfileGrant(
            associatedDomains = ASSOCIATED_DOMAINS in entitlements,
            applicationIdentifier = entitlements[APPLICATION_IDENTIFIER]?.textContent?.trim(),
            teamIdentifier = entitlements[TEAM_IDENTIFIER]?.textContent?.trim(),
        )
    }

    /**
     * [baseEntitlements] (a plist, or `null` for none) plus the associated domains of [hosts] and,
     * when missing, the application and team identifiers the system matches the
     * `apple-app-site-association` file against.
     */
    fun entitlementsXml(
        baseEntitlements: String?,
        hosts: List<String>,
        applicationIdentifier: String?,
        teamIdentifier: String?,
    ): String {
        val base = baseEntitlements ?: EMPTY_PLIST
        val additions =
            buildString {
                appendLine("    <key>$ASSOCIATED_DOMAINS</key>")
                appendLine("    <array>")
                hosts.forEach { appendLine("        <string>applinks:$it</string>") }
                appendLine("    </array>")
                if (applicationIdentifier != null && APPLICATION_IDENTIFIER !in base) {
                    appendLine("    <key>$APPLICATION_IDENTIFIER</key>")
                    appendLine("    <string>$applicationIdentifier</string>")
                }
                if (teamIdentifier != null && TEAM_IDENTIFIER !in base) {
                    appendLine("    <key>$TEAM_IDENTIFIER</key>")
                    appendLine("    <string>$teamIdentifier</string>")
                }
            }
        val end = base.lastIndexOf("</dict>")
        require(end >= 0) { "Entitlements are not a plist dictionary" }
        return base.substring(0, end) + additions + base.substring(end)
    }

    /**
     * Prepares [appDir] for Universal Links and returns the entitlements to seal it with, or
     * [entitlements] unchanged when app links do not apply (no hosts, unsigned build, no profile
     * granting the entitlement, or entitlements that already declare associated domains, e.g.
     * `applinks:host?mode=developer`).
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun bundleEntitlements(
        declaredHosts: List<String>,
        appDir: File,
        entitlements: File?,
        provisioningProfile: File?,
        signed: Boolean,
        runTool: ExternalToolRunner,
        logger: Logger,
        workDir: File,
    ): File? {
        if (declaredHosts.isEmpty()) return entitlements
        val hosts = AppLinkHosts.normalize(declaredHosts)
        if (!signed) {
            logger.info("appLinks: unsigned build, Universal Links (associated domains) skipped.")
            return entitlements
        }
        if (provisioningProfile == null) {
            logger.warn(
                "appLinks: macOS Universal Links need macOS { provisioningProfile } granting Associated " +
                    "Domains (restricted entitlement); without it the https:// links keep opening in the browser.",
            )
            return entitlements
        }
        var decoded = ""
        runTool(
            MacUtils.security,
            listOf("cms", "-D", "-i", provisioningProfile.absolutePath),
            processStdout = { decoded = it },
        )
        val grant = parseProfile(decoded)
        if (!grant.associatedDomains) {
            logger.warn(
                "appLinks: the provisioning profile $provisioningProfile does not grant Associated Domains; " +
                    "enable the capability on the App ID and regenerate the profile. Universal Links skipped, " +
                    "since macOS refuses to launch an app claiming an entitlement its profile lacks.",
            )
            return entitlements
        }

        // Signed after the copy: the profile is part of the sealed resources.
        provisioningProfile.copyTo(appDir.resolve("Contents/embedded.provisionprofile"), overwrite = true)

        val base = entitlements?.readText()
        if (base != null && ASSOCIATED_DOMAINS in base) return entitlements
        workDir.mkdirs()
        return workDir.resolve("app-links.entitlements").apply {
            writeText(entitlementsXml(base, hosts, grant.applicationIdentifier, grant.teamIdentifier))
        }
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    /** A plist `<dict>`'s `<key>` → value element pairs. */
    private fun Element.dictEntries(): Map<String, Element> =
        childElements()
            .chunked(2)
            .filter { it.size == 2 && it[0].tagName == "key" }
            .associate { (key, value) -> key.textContent.trim() to value }

    private val EMPTY_PLIST =
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |</dict>
        |</plist>
        |
        """.trimMargin()
}
