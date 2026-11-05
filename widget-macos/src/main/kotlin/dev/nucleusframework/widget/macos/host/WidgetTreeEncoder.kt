package dev.nucleusframework.widget.macos.host

import dev.nucleusframework.widget.Alignment
import dev.nucleusframework.widget.ElementAction
import dev.nucleusframework.widget.FontWeight
import dev.nucleusframework.widget.ImageProvider
import dev.nucleusframework.widget.ImageSize
import dev.nucleusframework.widget.ResolvedModifier
import dev.nucleusframework.widget.Spacing
import dev.nucleusframework.widget.TextSize
import dev.nucleusframework.widget.WidgetActionSignatures
import dev.nucleusframework.widget.WidgetBackground
import dev.nucleusframework.widget.WidgetColor
import dev.nucleusframework.widget.WidgetElement
import dev.nucleusframework.widget.WidgetJson

/**
 * Turns a composed [WidgetElement] tree into the JSON the Nucleus widget extension draws with
 * SwiftUI (`NucleusWidgets.swift`, `Node`). Short keys, defaults left out: the extension reads the
 * file on every timeline reload.
 *
 * Every action that runs app code carries its element's signature ([WidgetActionSignatures]), so
 * a click on a render older than the state is dropped rather than run on another element. Inputs
 * have no submit step in WidgetKit: a toggle or a choice writes its value when clicked; text and
 * number fields (Windows only) are left out.
 */
internal object WidgetTreeEncoder {
    /** [root] as the extension's JSON; [resolveImage] gives a store-relative path, or `null` to leave it out. */
    fun encode(
        root: WidgetElement,
        resolveImage: (ImageProvider) -> String?,
    ): String {
        val context = Context(resolveImage, WidgetActionSignatures.of(root))
        return WidgetJson.write(mapOf("v" to 1, "root" to context.node(unwrap(root))))
    }

    /**
     * The content's own column when it is all there is: the composition wraps the content in a
     * root column, and the extension turns the root's background into the widget's container
     * background, which a nested column would draw as an inset box instead.
     */
    private fun unwrap(root: WidgetElement): WidgetElement {
        val only = (root as? WidgetElement.Column)?.children?.singleOrNull()
        return if (only is WidgetElement.Column && root.modifier == ResolvedModifier()) only else root
    }

    private class Context(
        val resolveImage: (ImageProvider) -> String?,
        val signatures: Map<String, String>,
    )

    private fun Context.node(element: WidgetElement): Map<String, Any?>? {
        val json: MutableMap<String, Any?> =
            when (element) {
                is WidgetElement.Column ->
                    linkedMapOf(
                        "t" to "col",
                        "c" to element.children.mapNotNull { node(it) },
                        "sp" to element.spacing.json,
                        "ha" to element.horizontalAlignment.json,
                    )
                is WidgetElement.Row ->
                    linkedMapOf(
                        "t" to "row",
                        "c" to element.children.mapNotNull { node(it) },
                        "sp" to element.spacing.json,
                        "va" to element.verticalAlignment.json,
                    )
                is WidgetElement.Text ->
                    linkedMapOf<String, Any?>(
                        "t" to "text",
                        "s" to element.text,
                        "fs" to element.style.fontSize.json,
                        "fw" to element.style.fontWeight.json,
                        "fc" to element.style.color.json,
                    ).apply { if (element.maxLines != Int.MAX_VALUE) put("ml", element.maxLines) }
                is WidgetElement.Button ->
                    linkedMapOf<String, Any?>(
                        "t" to "button",
                        "s" to element.text,
                        "a" to action(element.action),
                    ).apply { if (!element.enabled) put("en", false) }
                is WidgetElement.Image -> {
                    val path = resolveImage(element.provider) ?: return null
                    linkedMapOf<String, Any?>("t" to "img", "src" to path, "sz" to element.size.json).apply {
                        element.contentDescription?.let { put("cd", it) }
                    }
                }
                is WidgetElement.Toggle, is WidgetElement.ChoiceInput -> input(element)
                // Windows-only text and number entry (widget-windows): WidgetKit has none, and a
                // field that cannot be typed in would only open the app when clicked.
                is WidgetElement.TextInput, is WidgetElement.NumberInput -> return null
                is WidgetElement.Spacer -> linkedMapOf("t" to "spacer")
            }
        modifier(element.modifier)?.let { json["m"] = it }
        return json
    }

    // WidgetKit inputs write their value when clicked: a toggle flips, a choice is one button per option.
    private fun input(element: WidgetElement): MutableMap<String, Any?> =
        when (element) {
            is WidgetElement.Toggle ->
                linkedMapOf(
                    "t" to "toggle",
                    "s" to element.title,
                    "on" to element.value,
                    "a" to mapOf("in" to mapOf(element.id to (!element.value).toString())),
                )
            is WidgetElement.ChoiceInput ->
                linkedMapOf<String, Any?>(
                    "t" to "choice",
                    "id" to element.id,
                    "on" to element.isExpanded,
                    "ch" to
                        element.choices.map {
                            mapOf(
                                "t" to it.title,
                                "v" to it.value,
                                "a" to mapOf("in" to mapOf(element.id to it.value)),
                            )
                        },
                ).apply {
                    element.value?.let { put("v", it) }
                    element.label?.let { put("label", it) }
                }
            else -> error("Not an input: $element")
        }

    private fun Context.modifier(modifier: ResolvedModifier): Map<String, Any?>? {
        val json = linkedMapOf<String, Any?>()
        if (modifier.fillMaxWidth) json["fw"] = true
        if (modifier.fillMaxHeight) json["fh"] = true
        modifier.weight?.let { json["w"] = it }
        modifier.background?.takeIf { it != WidgetBackground.Default }?.let { json["bg"] = it.json }
        modifier.onClick?.let { json["click"] = action(it) }
        return json.ifEmpty { null }
    }

    private fun Context.action(action: ElementAction): Map<String, Any?> =
        when (action) {
            is ElementAction.Execute ->
                linkedMapOf<String, Any?>("k" to action.key).apply { signatures[action.key]?.let { put("sig", it) } }
            is ElementAction.OpenUrl -> mapOf("url" to action.url)
        }

    private val Spacing.json get() = name.replaceFirstChar(Char::lowercaseChar)

    private val Alignment.Horizontal.json
        get() =
            when (this) {
                Alignment.Horizontal.Start -> "start"
                Alignment.Horizontal.CenterHorizontally -> "center"
                Alignment.Horizontal.End -> "end"
            }

    private val Alignment.Vertical.json
        get() =
            when (this) {
                Alignment.Vertical.Top -> "top"
                Alignment.Vertical.CenterVertically -> "center"
                Alignment.Vertical.Bottom -> "bottom"
            }

    private val TextSize.json get() = name.replaceFirstChar(Char::lowercaseChar)

    private val FontWeight.json get() = name.lowercase()

    private val WidgetColor.json get() = name.lowercase()

    private val WidgetBackground.json get() = name.lowercase()

    private val ImageSize.json get() = name.lowercase()
}
