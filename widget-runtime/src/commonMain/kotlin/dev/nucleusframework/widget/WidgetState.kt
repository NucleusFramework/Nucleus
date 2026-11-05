package dev.nucleusframework.widget

/**
 * Typed key of a [WidgetState] value. Create keys with [intStateKey], [longStateKey],
 * [doubleStateKey], [booleanStateKey] or [stringStateKey].
 */
public class WidgetStateKey<T : Any> internal constructor(
    public val name: String,
    internal val type: Char,
    internal val parse: (String) -> T?,
) {
    override fun equals(other: Any?): Boolean = other is WidgetStateKey<*> && other.name == name && other.type == type

    override fun hashCode(): Int = name.hashCode() * 31 + type.code

    override fun toString(): String = name
}

/** An `Int` state key. */
public fun intStateKey(name: String): WidgetStateKey<Int> = WidgetStateKey(name, 'i', String::toIntOrNull)

/** A `Long` state key. */
public fun longStateKey(name: String): WidgetStateKey<Long> = WidgetStateKey(name, 'l', String::toLongOrNull)

/** A `Double` state key. */
public fun doubleStateKey(name: String): WidgetStateKey<Double> = WidgetStateKey(name, 'd', String::toDoubleOrNull)

/** A `Boolean` state key. */
public fun booleanStateKey(name: String): WidgetStateKey<Boolean> =
    WidgetStateKey(name, 'b', String::toBooleanStrictOrNull)

/** A `String` state key. */
public fun stringStateKey(name: String): WidgetStateKey<String> = WidgetStateKey(name, 's') { it }

/**
 * The state of one pinned widget: typed values persisted by the widget host with the widget
 * itself (on Windows, its custom state), so they survive the app being closed or updated.
 */
public open class WidgetState internal constructor(
    internal val values: MutableMap<String, String> = mutableMapOf(),
) {
    /** The value of [key], or `null` if it was never set. */
    public operator fun <T : Any> get(key: WidgetStateKey<T>): T? = values[key.storageName]?.let(key.parse)

    /** Whether [key] has a value. */
    public operator fun contains(key: WidgetStateKey<*>): Boolean = key.storageName in values

    /** Returns a mutable copy of this state. */
    public fun toMutable(): MutableWidgetState = MutableWidgetState(values.toMutableMap())

    /** Encodes the state for the host's persistence (a flat JSON object). */
    @InternalNucleusWidgetApi
    public fun encode(): String = FlatJson.encode(values)

    override fun equals(other: Any?): Boolean = other is WidgetState && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()

    /** Decoding and the empty state. */
    public companion object {
        /** A state with no value. */
        public val Empty: WidgetState get() = WidgetState()

        /** Decodes a state written by [encode]; anything unreadable decodes as empty. */
        @InternalNucleusWidgetApi
        public fun decode(encoded: String?): MutableWidgetState =
            MutableWidgetState(encoded?.let(FlatJson::decode)?.toMutableMap() ?: mutableMapOf())
    }
}

/** A [WidgetState] that can be changed. */
public class MutableWidgetState internal constructor(
    values: MutableMap<String, String> = mutableMapOf(),
) : WidgetState(values) {
    /** Sets [key] to [value]; `null` removes it. */
    public operator fun <T : Any> set(
        key: WidgetStateKey<T>,
        value: T?,
    ) {
        if (value == null) values.remove(key.storageName) else values[key.storageName] = value.toString()
    }

    /** Removes [key]. */
    public fun remove(key: WidgetStateKey<*>) {
        values.remove(key.storageName)
    }

    /** Removes every value. */
    public fun clear() {
        values.clear()
    }

    internal fun replaceWith(other: WidgetState) {
        values.clear()
        values.putAll(other.values)
    }
}

// The type tag keeps two keys of the same name and different types apart. Inputs use it as their
// Adaptive Card id, so a submitted value lands straight in the state.
internal val WidgetStateKey<*>.storageName: String get() = "$type:$name"

/** Minimal flat `{"k":"v"}` JSON codec: the state needs nothing more and no JSON dependency. */
internal object FlatJson {
    private const val HEX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4

    fun encode(values: Map<String, String>): String =
        values.entries.joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:${quote(value)}" }

    fun quote(value: String): String =
        buildString(value.length + 2) {
            append('"')
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (c < ' ') {
                            append("\\u").append(c.code.toString(HEX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                        } else {
                            append(c)
                        }
                }
            }
            append('"')
        }

    @Suppress("ReturnCount")
    fun decode(text: String): Map<String, String>? {
        val reader = Reader(text.trim())
        if (!reader.consume('{')) return null
        val result = mutableMapOf<String, String>()
        if (reader.consume('}')) return result
        while (true) {
            val key = reader.string() ?: return null
            if (!reader.consume(':')) return null
            // Inputs submit numbers and booleans unquoted; `null` means no value.
            val value = reader.string() ?: reader.literal() ?: return null
            if (value != "null") result[key] = value
            if (reader.consume('}')) return result
            if (!reader.consume(',')) return null
        }
    }

    private class Reader(
        private val text: String,
    ) {
        private var index = 0

        fun consume(c: Char): Boolean {
            while (index < text.length && text[index].isWhitespace()) index++
            if (index < text.length && text[index] == c) {
                index++
                return true
            }
            return false
        }

        /** A bare JSON number, `true`, `false` or `null`, as its text. */
        fun literal(): String? {
            while (index < text.length && text[index].isWhitespace()) index++
            val start = index
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] in "+-.")) index++
            return text.substring(start, index).takeIf { it.isNotEmpty() }
        }

        @Suppress("ReturnCount", "MagicNumber")
        fun string(): String? {
            if (!consume('"')) return null
            val out = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) return null
                        when (val escaped = text[index++]) {
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) return null
                                out.append(text.substring(index, index + 4).toIntOrNull(16)?.toChar() ?: return null)
                                index += 4
                            }
                            else -> out.append(escaped)
                        }
                    }
                    else -> out.append(c)
                }
            }
            return null
        }
    }
}
