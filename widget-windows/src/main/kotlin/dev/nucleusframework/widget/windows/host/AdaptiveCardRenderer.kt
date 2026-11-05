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
@Suppress("TooManyFunctions") // one function per Adaptive Card element type
internal object AdaptiveCardRenderer {
    private const val SCHEMA = "http://adaptivecards.io/schemas/adaptive-card.json"

    /**
     * Renders [root]; [resolveImage] turns an app-resource image path into a URL the host can load
     * (the installed file's `file:///` URI), or `null` to embed the file instead. [webUrl] is the
     * page of a `WindowsWebWidget`, shown instead of the body where the host shows web content.
     * [revision] is sent back with every action ([REVISION_INPUT]), so a click on a card older
     * than the state can be told apart.
     */
    fun render(
        root: WidgetElement,
        resolveImage: (String) -> ImageSource = { ImageSource.Missing },
        webUrl: String? = null,
        revision: Long? = null,
    ): String {
        val context = Context(resolveImage, revision)
        val body =
            with(context) {
                when (root) {
                    is WidgetElement.Column -> items(root.children, root.spacing, root.horizontalAlignment)
                    else -> listOfNotNull(element(root))
                }
            }
        val card =
            linkedMapOf<String, Any?>(
                "type" to "AdaptiveCard",
                "\$schema" to SCHEMA,
                "version" to VERSION,
                "body" to body,
            )
        // A web widget is still an Adaptive Card: the body is the fallback where the host
        // cannot show web content.
        if (webUrl != null) card["metadata"] = linkedMapOf("webUrl" to webUrl)
        return Json.write(card)
    }

    /** Where an app-resource image is read from. */
    sealed interface ImageSource {
        /** A URL the host loads itself (`file:///…` of the installed file). */
        data class Url(
            val url: String,
        ) : ImageSource

        /** The file's bytes, embedded as a data URI (outside a package). */
        class Bytes(
            val bytes: ByteArray,
            val mimeType: String,
        ) : ImageSource

        /** Not found: the image is left out. */
        data object Missing : ImageSource
    }

    private class Context(
        val resolveImage: (String) -> ImageSource,
        val revision: Long?,
    )

    private fun Context.items(
        children: List<WidgetElement>,
        spacing: Spacing,
        alignment: Alignment.Horizontal,
    ): List<Map<String, Any?>> =
        children
            .mapNotNull { child -> element(child)?.let { child to it } }
            .mapIndexed { index, (child, json) ->
                json.apply {
                    if (index > 0) put("spacing", spacing.json)
                    // A background wraps the element in a Container, which has no alignment: align the element.
                    if (child.acceptsHorizontalAlignment) alignable().putIfAbsent("horizontalAlignment", alignment.json)
                }
            }

    private fun Context.element(element: WidgetElement): MutableMap<String, Any?>? =
        when (element) {
            is WidgetElement.Column ->
                linkedMapOf<String, Any?>(
                    "type" to "Container",
                    "items" to items(element.children, element.spacing, element.horizontalAlignment),
                )
            is WidgetElement.Row -> row(element)
            is WidgetElement.Text -> text(element)
            is WidgetElement.Button -> button(element)
            is WidgetElement.Image -> image(element)
            is WidgetElement.TextInput, is WidgetElement.Toggle, is WidgetElement.ChoiceInput,
            is WidgetElement.NumberInput,
            -> input(element)
            is WidgetElement.Spacer ->
                linkedMapOf<String, Any?>("type" to "Container", "items" to emptyList<Any>(), "minHeight" to "8px")
        }?.also { decorate(it, element.modifier) }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.alignable(): MutableMap<String, Any?> =
        if (this["type"] == "Container") (this["items"] as List<MutableMap<String, Any?>>).single() else this

    private fun Context.row(element: WidgetElement.Row): MutableMap<String, Any?> =
        linkedMapOf(
            "type" to "ColumnSet",
            "columns" to
                element.children
                    .mapNotNull { child -> element(child)?.let { child to it } }
                    .mapIndexed { index, (child, json) ->
                        linkedMapOf<String, Any?>(
                            "type" to "Column",
                            "width" to child.modifier.columnWidth,
                            "verticalContentAlignment" to element.verticalAlignment.json,
                            "items" to listOf(json),
                        ).apply { if (index > 0) put("spacing", element.spacing.json) }
                    },
        )

    private fun text(element: WidgetElement.Text): MutableMap<String, Any?> =
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

    private fun Context.button(element: WidgetElement.Button): MutableMap<String, Any?> =
        linkedMapOf(
            "type" to "ActionSet",
            "actions" to
                listOf(
                    action(element.action).apply {
                        put("title", element.text)
                        if (!element.enabled) put("isEnabled", false)
                    },
                ),
        )

    private fun Context.image(element: WidgetElement.Image): MutableMap<String, Any?>? =
        imageUrl(element)?.let { url ->
            linkedMapOf<String, Any?>("type" to "Image", "url" to url).apply {
                // Adaptive Cards has no size below "small" (a 40 px avatar): an icon is pinned in pixels.
                if (element.size == ImageSize.Icon) {
                    put("width", ICON_SIZE)
                    put("height", ICON_SIZE)
                } else {
                    put("size", element.size.json)
                }
                element.contentDescription?.let { put("altText", it) }
            }
        }

    // Adaptive Card inputs: their id is the widget state key, their value comes back with the next action.
    private fun input(element: WidgetElement): MutableMap<String, Any?> =
        when (element) {
            is WidgetElement.TextInput ->
                linkedMapOf<String, Any?>("type" to "Input.Text", "id" to element.id).apply {
                    putOptional("value", element.value)
                    putOptional("label", element.label)
                    putOptional("placeholder", element.placeholder)
                    if (element.isMultiline) put("isMultiline", true)
                    putOptional("maxLength", element.maxLength)
                }
            is WidgetElement.Toggle ->
                linkedMapOf(
                    "type" to "Input.Toggle",
                    "id" to element.id,
                    "title" to element.title,
                    "value" to element.value.toString(),
                    "valueOn" to "true",
                    "valueOff" to "false",
                )
            is WidgetElement.ChoiceInput ->
                linkedMapOf<String, Any?>(
                    "type" to "Input.ChoiceSet",
                    "id" to element.id,
                    "style" to if (element.isExpanded) "expanded" else "compact",
                    "choices" to element.choices.map { linkedMapOf("title" to it.title, "value" to it.value) },
                ).apply {
                    putOptional("value", element.value)
                    putOptional("label", element.label)
                }
            is WidgetElement.NumberInput ->
                linkedMapOf<String, Any?>("type" to "Input.Number", "id" to element.id).apply {
                    putOptional("value", element.value)
                    putOptional("label", element.label)
                    putOptional("min", element.min)
                    putOptional("max", element.max)
                }
            else -> error("Not an input: $element")
        }

    private fun MutableMap<String, Any?>.putOptional(
        key: String,
        value: Any?,
    ) {
        if (value != null) put(key, value)
    }

    private fun Context.imageUrl(element: WidgetElement.Image): String? {
        val provider = element.provider
        provider.url?.let { return it }
        provider.bytes?.let { return dataUri(it, provider.mimeType ?: "image/png") }
        val path = provider.appResource ?: return null
        return when (val source = resolveImage(path)) {
            is ImageSource.Url -> source.url
            is ImageSource.Bytes -> dataUri(source.bytes, source.mimeType)
            ImageSource.Missing -> null
        }
    }

    private fun Context.decorate(
        json: MutableMap<String, Any?>,
        modifier: ResolvedModifier,
    ) = json.apply {
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

    private fun Context.action(action: ElementAction): MutableMap<String, Any?> =
        when (action) {
            is ElementAction.Execute ->
                linkedMapOf<String, Any?>("type" to "Action.Execute", "verb" to action.key).apply {
                    if (revision != null) put("data", linkedMapOf(REVISION_INPUT to revision))
                }
            is ElementAction.OpenUrl -> linkedMapOf<String, Any?>("type" to "Action.OpenUrl", "url" to action.url)
        }

    private fun dataUri(
        bytes: ByteArray,
        mimeType: String,
    ): String {
        if (bytes.size > LARGE_EMBEDDED_IMAGE) {
            logger.warning {
                "A ${bytes.size / BYTES_PER_KB} KB image is embedded in a widget card and re-sent with every " +
                    "update: ship it with ImageProvider.appResource instead"
            }
        }
        return "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    /** The action data key carrying the revision of the card that was clicked. */
    const val REVISION_INPUT = "nucleus.revision"
    private const val VERSION = "1.5"
    private const val ICON_SIZE = "20px"
    private const val BYTES_PER_KB = 1024
    private const val LARGE_EMBEDDED_IMAGE = 64 * BYTES_PER_KB
    private val logger =
        java.util.logging.Logger
            .getLogger(AdaptiveCardRenderer::class.java.name)

    // A Row is a ColumnSet, which takes the alignment too: without it a centered Column's rows
    // (a row of buttons under a centered value) stay at the left edge.
    private val WidgetElement.acceptsHorizontalAlignment: Boolean
        get() =
            this is WidgetElement.Text ||
                this is WidgetElement.Image ||
                this is WidgetElement.Button ||
                this is WidgetElement.Row

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
            is Double -> if (value % 1.0 == 0.0) out.append(value.toLong()) else out.append(value)
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
