package dev.nucleusframework.widget.windows.host

import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.ElementAction
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.ImageSize
import dev.nucleusframework.widget.ResolvedModifier
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetElement
import java.util.Base64

/**
 * Turns a composed [WidgetElement] tree into an Adaptive Card 1.5 template, the format the
 * Windows widgets board renders. The values are written into the template itself: the data
 * payload stays `{}`, since every render is a full snapshot.
 */
internal object AdaptiveCardRenderer {
    private const val SCHEMA = "http://adaptivecards.io/schemas/adaptive-card.json"

    fun render(root: WidgetElement): String {
        val body =
            when (root) {
                is WidgetElement.Column -> items(root.children, root.spacing, root.horizontalAlignment)
                else -> listOf(element(root))
            }
        return Json.write(
            linkedMapOf(
                "type" to "AdaptiveCard",
                "\$schema" to SCHEMA,
                "version" to "1.5",
                "body" to body,
            ),
        )
    }

    private fun items(
        children: List<WidgetElement>,
        spacing: Spacing,
        alignment: Alignment.Horizontal,
    ): List<Map<String, Any?>> =
        children.mapIndexed { index, child ->
            element(child).apply {
                if (index > 0) put("spacing", spacing.json)
                if (child.acceptsHorizontalAlignment) putIfAbsent("horizontalAlignment", alignment.json)
            }
        }

    private fun element(element: WidgetElement): MutableMap<String, Any?> =
        when (element) {
            is WidgetElement.Column ->
                linkedMapOf<String, Any?>(
                    "type" to "Container",
                    "items" to items(element.children, element.spacing, element.horizontalAlignment),
                )
            is WidgetElement.Row ->
                linkedMapOf<String, Any?>(
                    "type" to "ColumnSet",
                    "columns" to
                        element.children.mapIndexed { index, child ->
                            linkedMapOf<String, Any?>(
                                "type" to "Column",
                                "width" to child.modifier.columnWidth,
                                "verticalContentAlignment" to element.verticalAlignment.json,
                                "items" to listOf(element(child)),
                            ).apply { if (index > 0) put("spacing", element.spacing.json) }
                        },
                )
            is WidgetElement.Text ->
                linkedMapOf<String, Any?>(
                    "type" to "TextBlock",
                    "text" to element.text,
                    "wrap" to true,
                    "size" to element.style.fontSize.json,
                    "weight" to element.style.fontWeight.json,
                    "color" to element.style.color.json,
                ).apply {
                    if (element.style.color == WidgetColor.Subtle) put("isSubtle", true)
                    if (element.maxLines != Int.MAX_VALUE) put("maxLines", element.maxLines)
                }
            is WidgetElement.Button ->
                linkedMapOf<String, Any?>(
                    "type" to "ActionSet",
                    "actions" to
                        listOf(
                            action(element.action).apply {
                                put("title", element.text)
                                if (!element.enabled) put("isEnabled", false)
                            },
                        ),
                )
            is WidgetElement.Image ->
                linkedMapOf<String, Any?>(
                    "type" to "Image",
                    "url" to (element.provider.url ?: dataUri(element.provider.bytes!!, element.provider.mimeType!!)),
                    "size" to element.size.json,
                ).apply { element.contentDescription?.let { put("altText", it) } }
            is WidgetElement.Spacer ->
                linkedMapOf<String, Any?>("type" to "Container", "items" to emptyList<Any>(), "minHeight" to "8px")
        }.apply { decorate(element.modifier) }

    private fun MutableMap<String, Any?>.decorate(modifier: ResolvedModifier) {
        if (modifier.fillMaxHeight) put("height", "stretch")
        modifier.background?.let { background ->
            // Only containers take a style: wrap anything else in one.
            if (this["type"] == "Container") {
                put("style", background.json)
            } else {
                val inner = LinkedHashMap(this)
                clear()
                putAll(
                    linkedMapOf<String, Any?>(
                        "type" to "Container",
                        "style" to background.json,
                        "items" to listOf(inner),
                    ),
                )
            }
        }
        modifier.onClick?.let { put("selectAction", action(it)) }
    }

    private fun action(action: ElementAction): MutableMap<String, Any?> =
        when (action) {
            is ElementAction.Execute -> linkedMapOf<String, Any?>("type" to "Action.Execute", "verb" to action.key)
            is ElementAction.OpenUrl -> linkedMapOf<String, Any?>("type" to "Action.OpenUrl", "url" to action.url)
        }

    private fun dataUri(
        bytes: ByteArray,
        mimeType: String,
    ) = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes)

    private val WidgetElement.acceptsHorizontalAlignment: Boolean
        get() = this is WidgetElement.Text || this is WidgetElement.Image || this is WidgetElement.Button

    private val ResolvedModifier.columnWidth: Any
        get() = weight?.toString() ?: if (fillMaxWidth) "stretch" else "auto"

    private val Spacing.json get() = name.replaceFirstChar(Char::lowercaseChar)

    private val Alignment.Horizontal.json
        get() =
            when (this) {
                Alignment.Horizontal.Start -> "left"
                Alignment.Horizontal.CenterHorizontally -> "center"
                Alignment.Horizontal.End -> "right"
            }

    private val Alignment.Vertical.json
        get() =
            when (this) {
                Alignment.Vertical.Top -> "top"
                Alignment.Vertical.CenterVertically -> "center"
                Alignment.Vertical.Bottom -> "bottom"
            }

    private val TextSize.json get() = name.replaceFirstChar(Char::lowercaseChar)

    private val FontWeight.json
        get() =
            when (this) {
                FontWeight.Light -> "lighter"
                FontWeight.Normal -> "default"
                FontWeight.Bold -> "bolder"
            }

    private val WidgetColor.json
        get() =
            when (this) {
                WidgetColor.Default, WidgetColor.Subtle -> "default"
                WidgetColor.Accent -> "accent"
                WidgetColor.Good -> "good"
                WidgetColor.Warning -> "warning"
                WidgetColor.Attention -> "attention"
            }

    private val WidgetBackground.json get() = name.replaceFirstChar(Char::lowercaseChar)

    private val ImageSize.json get() = name.replaceFirstChar(Char::lowercaseChar)
}

/** Minimal JSON writer for the card maps: strings, booleans, numbers, lists, maps. */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(
        out: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean, is Int, is Long -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    quote(out, key.toString())
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    private fun quote(
        out: StringBuilder,
        value: String,
    ) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
