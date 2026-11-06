package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.MacAppExtension
import dev.nucleusframework.desktop.application.dsl.MacWidgetsSettings
import org.gradle.api.GradleException
import java.io.File
import java.util.zip.ZipFile

/**
 * Turns `macOS { widgets { } }` into the app's widget extension: the prebuilt Nucleus extension of
 * the `nucleus.widget-macos` JAR (`nucleus/native/darwin-<arch>/nucleus-widget-extension/`: the
 * executable and its AppIntents metadata), wrapped in a `.appex` whose Info.plist lists the widgets
 * and tells the extension where the store it shares with the app is, plus the extension's sandbox
 * entitlements. The bundle then goes through the `appExtensions` signing pipeline.
 */
internal object MacWidgetsPackaging {
    const val EXTENSION_NAME = "NucleusWidgets"
    private const val JAR_DIR = "nucleus-widget-extension"
    private const val MIN_MACOS = "14.0"

    /** The extension's bundle id: WidgetKit requires the app's as its prefix. */
    fun extensionBundleId(appBundleId: String): String = "$appBundleId.$EXTENSION_NAME"

    /** Home-relative directory of the store, when no App Group is set. */
    fun storeHomePath(appBundleId: String): String = "Library/Application Support/$appBundleId/NucleusWidgets"

    /**
     * Why the app cannot reach the store, or `null`: an [appGroup] the app's own entitlements
     * ([appEntitlements], the text of `macOS { entitlementsFile }`) do not list. The extension gets
     * the group from the plugin; the app's entitlements are the app's, so they are only checked.
     */
    fun appGroupWarning(
        appGroup: String?,
        appEntitlements: String?,
    ): String? =
        if (appGroup == null || appEntitlements?.contains("<string>$appGroup</string>") == true) {
            null
        } else {
            "macOS widgets share their files through the App Group '$appGroup', but the app's entitlements " +
                "(macOS { entitlementsFile }) do not list it under com.apple.security.application-groups: " +
                "the app will not reach the widget store and every widget stays empty."
        }

    /** The `appExtensions` entry for the bundle [assemble] writes into [outputDir]. */
    fun appExtension(outputDir: File): MacAppExtension =
        MacAppExtension(EXTENSION_NAME).apply {
            appex(outputDir.resolve("$EXTENSION_NAME.appex"))
            entitlements(outputDir.resolve("$EXTENSION_NAME.entitlements"))
        }

    /** The JAR entries prefix of the extension built for [arch] (`aarch64` / `x64`). */
    fun jarPrefix(arch: String): String = "nucleus/native/darwin-$arch/$JAR_DIR/"

    /**
     * Writes `NucleusWidgets.appex` and `NucleusWidgets.entitlements` into [outputDir], the
     * extension taken from the first of [runtimeJars] that ships one for [arch].
     */
    @Suppress("LongParameterList")
    fun assemble(
        widgets: MacWidgetsSettings,
        appBundleId: String,
        version: String,
        arch: String,
        runtimeJars: Iterable<File>,
        outputDir: File,
    ) {
        val prefix = jarPrefix(arch)
        val jar =
            runtimeJars.firstOrNull { file ->
                file.isFile &&
                    file.extension == "jar" &&
                    ZipFile(file).use { it.getEntry(prefix + EXTENSION_NAME) != null }
            } ?: throw GradleException(
                "macOS { widgets { } } declares widgets but no nucleus.widget-macos JAR with a $arch extension is " +
                    "on the runtime classpath: add implementation(\"dev.nucleusframework:nucleus.widget-macos\")",
            )

        outputDir.deleteRecursively()
        val contents = outputDir.resolve("$EXTENSION_NAME.appex/Contents").apply { mkdirs() }
        ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val relative = entry.name.removePrefix(prefix)
                val target =
                    if (relative == EXTENSION_NAME) {
                        contents.resolve("MacOS/$EXTENSION_NAME")
                    } else {
                        contents.resolve("Resources/$relative")
                    }
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        contents.resolve("MacOS/$EXTENSION_NAME").setExecutable(true)
        contents.resolve("Info.plist").writeText(infoPlist(widgets, appBundleId, version))
        outputDir.resolve("$EXTENSION_NAME.entitlements").writeText(entitlements(widgets, appBundleId))
    }

    fun infoPlist(
        widgets: MacWidgetsSettings,
        appBundleId: String,
        version: String,
    ): String =
        plist(
            buildString {
                key("CFBundleDevelopmentRegion", "en")
                key("CFBundleDisplayName", "Widgets")
                key("CFBundleExecutable", EXTENSION_NAME)
                key("CFBundleIdentifier", extensionBundleId(appBundleId))
                key("CFBundleInfoDictionaryVersion", "6.0")
                key("CFBundleName", EXTENSION_NAME)
                key("CFBundlePackageType", "XPC!")
                key("CFBundleShortVersionString", version)
                key("CFBundleVersion", version)
                append("<key>CFBundleSupportedPlatforms</key><array><string>MacOSX</string></array>\n")
                key("LSMinimumSystemVersion", MIN_MACOS)
                append("<key>NSExtension</key><dict>\n")
                key("NSExtensionPointIdentifier", "com.apple.widgetkit-extension")
                append("</dict>\n")
                append("<key>NucleusWidgets</key><dict>\n")
                key("AppBundleIdentifier", appBundleId)
                val group = widgets.appGroup
                if (group != null) key("StoreAppGroup", group) else key("StoreHomePath", storeHomePath(appBundleId))
                append("<key>Widgets</key><array>\n")
                for (widget in widgets.definitions) {
                    append("<dict>\n")
                    key("Kind", widget.kind)
                    key("DisplayName", widget.displayName ?: widget.kind)
                    key("Description", widget.description.orEmpty())
                    append("<key>Families</key><array>")
                    widget.families.distinct().forEach { append("<string>${escape(it.rawValue)}</string>") }
                    append("</array>\n</dict>\n")
                }
                append("</array>\n</dict>\n")
            },
        )

    /**
     * WidgetKit extensions must be sandboxed. The store is reached through the App Group, or a
     * temporary exception on its home directory (which App Review rejects, hence the group there).
     */
    fun entitlements(
        widgets: MacWidgetsSettings,
        appBundleId: String,
    ): String =
        plist(
            buildString {
                append("<key>com.apple.security.app-sandbox</key><true/>\n")
                val group = widgets.appGroup
                if (group != null) {
                    append("<key>com.apple.security.application-groups</key>")
                    append("<array><string>${escape(group)}</string></array>\n")
                } else {
                    append("<key>com.apple.security.temporary-exception.files.home-relative-path.read-write</key>")
                    append("<array><string>/${escape(storeHomePath(appBundleId))}/</string></array>\n")
                }
            },
        )

    private fun StringBuilder.key(
        name: String,
        value: String,
    ) {
        append("<key>").append(name).append("</key><string>").append(escape(value)).append("</string>\n")
    }

    private fun plist(body: String): String =
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        |<plist version="1.0">
        |<dict>
        |
        """.trimMargin() + body + "</dict>\n</plist>\n"

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
