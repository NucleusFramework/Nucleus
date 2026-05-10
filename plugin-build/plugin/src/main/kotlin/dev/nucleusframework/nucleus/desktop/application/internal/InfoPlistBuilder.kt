/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal

import dev.nucleusframework.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistBooleanValue
import dev.nucleusframework.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistListValue
import dev.nucleusframework.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistMapValue
import dev.nucleusframework.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistStringValue
import java.io.File
import kotlin.reflect.KProperty

private const val INDENT = "  "

private fun indentForLevel(level: Int) = INDENT.repeat(level)

internal class InfoPlistBuilder(
    private val extraPlistKeysRawXml: String? = null,
) {
    internal sealed class InfoPlistValue {
        abstract fun asPlistEntry(nestingLevel: Int): String

        data class InfoPlistListValue(
            val elements: List<InfoPlistValue>,
        ) : InfoPlistValue() {
            override fun asPlistEntry(nestingLevel: Int): String =
                if (elements.isEmpty()) {
                    "${indentForLevel(nestingLevel)}<array/>"
                } else {
                    elements.joinToString(
                        separator = "\n",
                        prefix = "${indentForLevel(nestingLevel)}<array>\n",
                        postfix = "\n${indentForLevel(nestingLevel)}</array>",
                    ) {
                        it.asPlistEntry(nestingLevel + 1)
                    }
                }

            constructor(vararg elements: InfoPlistValue) : this(elements.asList())
        }

        data class InfoPlistMapValue(
            val elements: Map<InfoPlistKey, InfoPlistValue>,
        ) : InfoPlistValue() {
            override fun asPlistEntry(nestingLevel: Int): String =
                if (elements.isEmpty()) {
                    "${indentForLevel(nestingLevel)}<dict/>"
                } else {
                    elements.entries.joinToString(
                        separator = "\n",
                        prefix = "${indentForLevel(nestingLevel)}<dict>\n",
                        postfix = "\n${indentForLevel(nestingLevel)}</dict>",
                    ) { (key, value) ->
                        "${indentForLevel(nestingLevel + 1)}<key>${key.name}</key>\n${value.asPlistEntry(nestingLevel + 1)}"
                    }
                }

            constructor(vararg elements: Pair<InfoPlistKey, InfoPlistValue>) : this(elements.toMap())
        }

        data class InfoPlistStringValue(
            val value: String,
        ) : InfoPlistValue() {
            override fun asPlistEntry(nestingLevel: Int): String =
                if (value.isEmpty()) {
                    "${indentForLevel(
                        nestingLevel,
                    )}<string/>"
                } else {
                    "${indentForLevel(nestingLevel)}<string>$value</string>"
                }
        }

        data class InfoPlistBooleanValue(
            val value: Boolean,
        ) : InfoPlistValue() {
            override fun asPlistEntry(nestingLevel: Int): String {
                val tag = if (value) "true" else "false"
                return "${indentForLevel(nestingLevel)}<$tag/>"
            }
        }
    }

    private val values = LinkedHashMap<InfoPlistKey, InfoPlistValue>()

    operator fun get(key: InfoPlistKey): InfoPlistValue? = values[key]

    operator fun set(
        key: InfoPlistKey,
        value: String?,
    ) = set(key, value?.let(::InfoPlistStringValue))

    operator fun set(
        key: InfoPlistKey,
        value: List<InfoPlistValue>?,
    ) = set(key, value?.let(::InfoPlistListValue))

    operator fun set(
        key: InfoPlistKey,
        value: Map<InfoPlistKey, InfoPlistValue>?,
    ) = set(key, value?.let(::InfoPlistMapValue))

    operator fun set(
        key: InfoPlistKey,
        value: Boolean?,
    ) = set(key, value?.let(::InfoPlistBooleanValue))

    operator fun set(
        key: InfoPlistKey,
        value: InfoPlistValue?,
    ) {
        if (value != null) {
            values[key] = value
        } else {
            values.remove(key)
        }
    }

    fun writeToFile(file: File) {
        file.writer().buffered().use { writer ->
            writer.run {
                appendLine("<?xml version=\"1.0\" ?>")
                appendLine("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
                    "\"https://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
                appendLine("<plist version=\"1.0\">")
                appendLine("${indentForLevel(1)}<dict>")
                for ((k, v) in values) {
                    appendLine("${indentForLevel(2)}<key>${k.name}</key>")
                    appendLine(v.asPlistEntry(2))
                }
                extraPlistKeysRawXml?.let { appendLine(it) }
                appendLine("${indentForLevel(1)}</dict>")
                appendLine("</plist>")
            }
        }
    }
}

internal data class InfoPlistKey(
    val name: String,
)

internal object PlistKeys {
    private operator fun getValue(
        thisRef: PlistKeys,
        property: KProperty<*>,
    ): InfoPlistKey = InfoPlistKey(property.name)

    val LSMinimumSystemVersion by this
    val CFBundleDevelopmentRegion by this
    val CFBundleAllowMixedLocalizations by this
    val CFBundleDocumentTypes by this
    val CFBundleTypeRole by this
    val CFBundleTypeExtensions by this
    val CFBundleTypeIconFile by this
    val CFBundleTypeMIMETypes by this
    val CFBundleTypeName by this
    val CFBundleTypeOSTypes by this
    val CFBundleExecutable by this
    val CFBundleIconFile by this
    val CFBundleIconName by this
    val CFBundleIdentifier by this
    val CFBundleInfoDictionaryVersion by this
    val CFBundleName by this
    val CFBundleDisplayName by this
    val CFBundlePackageType by this
    val CFBundleShortVersionString by this
    val CFBundleSignature by this
    val LSApplicationCategoryType by this
    val CFBundleVersion by this
    val NSHumanReadableCopyright by this
    val NSSupportsAutomaticGraphicsSwitching by this
    val NSHighResolutionCapable by this
    val ITSAppUsesNonExemptEncryption by this
    val CFBundleURLTypes by this
    val CFBundleURLName by this
    val CFBundleURLSchemes by this
}
