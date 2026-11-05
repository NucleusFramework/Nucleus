package dev.nucleusframework.widget

/**
 * What every host needs to tell an outdated click apart. Action keys are positions (`a0`, `a1`, …),
 * so a host stamps each action it draws with what it stood for: the clicked element's kind and the
 * texts it shows (`button:Add`, `row:Buy milk`). A click runs only if its key still names the same
 * element once the widget is composed again from the current state.
 */
@InternalNucleusWidgetApi
public object WidgetActionSignatures {
    /** The signature of what each action key of [root] stands for: element kind and label. */
    public fun of(root: WidgetElement): Map<String, String> = buildMap { collect(root, this) }

    private fun collect(
        element: WidgetElement,
        out: MutableMap<String, String>,
    ) {
        if (element is WidgetElement.Button) {
            (element.action as? ElementAction.Execute)?.let { out[it.key] = "button:${element.text}" }
        }
        (element.modifier.onClick as? ElementAction.Execute)?.let { out[it.key] = "${kind(element)}:${label(element)}" }
        children(element).forEach { collect(it, out) }
    }

    private fun children(element: WidgetElement): List<WidgetElement> =
        when (element) {
            is WidgetElement.Column -> element.children
            is WidgetElement.Row -> element.children
            else -> emptyList()
        }

    private fun kind(element: WidgetElement): String =
        when (element) {
            is WidgetElement.Row -> "row"
            is WidgetElement.Column -> "column"
            is WidgetElement.Text -> "text"
            is WidgetElement.Image -> "image"
            else -> "element"
        }

    /**
     * The texts an element shows, to tell two clickable rows apart: a check icon's description is
     * the same on every row, so images only count when there is no text at all.
     */
    private fun label(element: WidgetElement): String =
        texts(element).joinToString("|").ifEmpty {
            (element as? WidgetElement.Image)?.contentDescription.orEmpty()
        }

    private fun texts(element: WidgetElement): List<String> =
        when (element) {
            is WidgetElement.Text -> listOf(element.text)
            is WidgetElement.Button -> listOf(element.text)
            else -> children(element).flatMap(::texts)
        }
}

/** Minimal JSON writer for what hosts send: strings, booleans, numbers, lists, maps, `null`. */
@InternalNucleusWidgetApi
public object WidgetJson {
    private const val HEX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4

    /** [value] as JSON; anything else than the types above is written as its string. */
    public fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

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
                else ->
                    if (c < ' ') {
                        out.append("\\u").append(c.code.toString(HEX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                    } else {
                        out.append(c)
                    }
            }
        }
        out.append('"')
    }
}
