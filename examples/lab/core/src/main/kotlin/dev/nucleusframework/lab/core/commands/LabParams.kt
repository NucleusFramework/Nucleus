package dev.nucleusframework.lab.core.commands

/**
 * Parameters sent to a probe (`nucleus-lab://probe/<id>?count=5&clear`), with typed reads:
 * a key that is absent or does not parse reads as `null`, so a probe applies only what it got.
 */
@JvmInline
value class LabParams(
    val values: Map<String, String>,
) {
    operator fun get(key: String): String? = values[key]

    fun int(key: String): Int? = values[key]?.toIntOrNull()

    fun long(key: String): Long? = values[key]?.toLongOrNull()

    fun double(key: String): Double? = values[key]?.toDoubleOrNull()

    /** `true` / `false` / `1` / `0` / `yes` / `no`; a bare `?key` reads as `true`. */
    fun bool(key: String): Boolean? =
        when (values[key]?.lowercase()) {
            null -> null
            "", "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }

    /** `?clear` present, whatever its value. */
    fun flag(key: String): Boolean = key in values

    /** Matches an enum constant by name, case-insensitively. */
    inline fun <reified T : Enum<T>> enum(key: String): T? =
        values[key]?.let { raw -> enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } }
}
