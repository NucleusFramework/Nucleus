package dev.nucleusframework.core.runtime

/**
 * A freedesktop `.desktop` file as text, with just enough structure for AppImage integration:
 * reading values from the `[Desktop Entry]` group, rewriting the launch lines to point at the
 * image, and setting a marker key. Everything else (comments, ordering, other groups) passes
 * through untouched, so the installed entry stays recognizably the packager's.
 */
internal class DesktopEntryText(
    text: String,
) {
    private val lines: List<String> = text.lines().dropLastWhile { it.isEmpty() }

    /** The raw value of [key] in the `[Desktop Entry]` group, or null. */
    fun value(key: String): String? =
        mainGroupLines()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')

    /**
     * Rewrites the entry to launch [imagePath]: every `Exec=` line becomes the quoted image path
     * followed by the original line's field codes (`%U` and friends; its arguments were AppRun
     * options that only made sense inside the mounted image, such as electron-builder's
     * `--no-sandbox`), and `TryExec=` lines are dropped for the same reason. [iconOverride], when
     * set, replaces the `Icon=` value with an absolute path (used when the image carries no
     * themed icon tree).
     */
    fun rewriteForImage(
        imagePath: String,
        iconOverride: String?,
    ): DesktopEntryText {
        val exec = "Exec=" + quoteExecArgument(imagePath)
        val rewritten =
            lines.mapNotNull { line ->
                when {
                    line.startsWith("Exec=") -> {
                        val fieldCodes =
                            line
                                .substringAfter('=')
                                .split(' ', '\t')
                                .filter { it.length == 2 && it[0] == '%' }
                        (listOf(exec) + fieldCodes).joinToString(" ")
                    }
                    line.startsWith("TryExec=") -> null
                    iconOverride != null && line.startsWith("Icon=") -> "Icon=$iconOverride"
                    else -> line
                }
            }
        return DesktopEntryText(rewritten.joinToString("\n"))
    }

    /** Sets [key] to [value] at the end of the `[Desktop Entry]` group, replacing an existing line. */
    fun withValue(
        key: String,
        value: String,
    ): DesktopEntryText {
        val withoutOld = lines.filterNot { it.startsWith("$key=") }
        val mainGroup = withoutOld.indexOf(MAIN_GROUP)
        val insertAt =
            if (mainGroup < 0) {
                withoutOld.size
            } else {
                // Just before the next group header, or at the end of the file.
                (mainGroup + 1 until withoutOld.size).firstOrNull { withoutOld[it].startsWith("[") }
                    ?: withoutOld.size
            }
        val updated = withoutOld.subList(0, insertAt) + "$key=$value" + withoutOld.subList(insertAt, withoutOld.size)
        return DesktopEntryText(updated.joinToString("\n"))
    }

    fun render(): String = lines.joinToString("\n", postfix = "\n")

    private fun mainGroupLines(): List<String> {
        val start = lines.indexOf(MAIN_GROUP)
        if (start < 0) return lines
        return lines.drop(start + 1).takeWhile { !it.startsWith("[") }
    }

    private companion object {
        const val MAIN_GROUP = "[Desktop Entry]"
    }
}

/**
 * Quotes one argument for a `.desktop` `Exec=` line per the Desktop Entry Specification, which
 * escapes twice: the argument is double-quoted with `\` `"` `` ` `` `$` backslash-escaped
 * (quoting rules), and then the whole value's backslashes are escaped again (general string
 * escaping), which is why a literal backslash becomes four.
 */
internal fun quoteExecArgument(argument: String): String {
    val quoted =
        buildString {
            append('"')
            for (c in argument) {
                if (c == '\\' || c == '"' || c == '`' || c == '$') append('\\')
                append(c)
            }
            append('"')
        }
    return quoted.replace("\\", "\\\\")
}
