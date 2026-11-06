package dev.nucleusframework.desktop.application.internal

/**
 * What a library rule file may contain before [ConsumerProguardRules] hands it to ProGuard: it may
 * only say what to keep. A file using any option outside [ALLOWED_OPTIONS] — spelled in full, since
 * ProGuard also accepts any prefix of an option (`-incl`, a bare `-`) — or the `@file` include is
 * refused. A deny-list would always miss one: whole-build switches, file and keystore options,
 * aliases (`-defaultpackage`). Anything the guard could split into words differently from ProGuard
 * (quotes, non-ASCII, a glued `#`, a stray `{`) is refused rather than parsed.
 */
internal object ConsumerRuleGuard {
    /** What a library may declare: keep rules, conditions, attributes, warnings, assumptions. */
    val ALLOWED_OPTIONS =
        setOf(
            "-keep",
            "-keepclassmembers",
            "-keepclasseswithmembers",
            "-keepnames",
            "-keepclassmembernames",
            "-keepclasseswithmembernames",
            "-if",
            "-keepattributes",
            "-keeppackagenames",
            "-keepparameternames",
            "-keepdirectories",
            "-keepkotlinmetadata",
            "-dontwarn",
            "-dontnote",
            "-assumenosideeffects",
            "-assumenoexternalsideeffects",
            "-assumenoescapingparameters",
            "-assumenoexternalreturnvalues",
            "-assumevalues",
            "-adaptclassstrings",
            "-adaptresourcefilenames",
            "-adaptresourcefilecontents",
        )

    /** A delimiter, or a run of anything else. */
    private val TOKEN = Regex("""[{}();,]|[^\s{}();,]+""")
    /** The [ALLOWED_OPTIONS] followed by a class specification, the only place a `{ … }` body opens. */
    private val CLASS_SPEC_OPTIONS =
        setOf(
            "-keep",
            "-keepclassmembers",
            "-keepclasseswithmembers",
            "-keepnames",
            "-keepclassmembernames",
            "-keepclasseswithmembernames",
            "-if",
            "-assumenosideeffects",
            "-assumenoexternalsideeffects",
            "-assumenoescapingparameters",
            "-assumenoexternalreturnvalues",
            "-assumevalues",
        )

    /** An annotation class name in a ProGuard pattern: a binary name, `*` and `?` wildcards allowed. */
    private val ANNOTATION_NAME = Regex("""[\p{L}_$*?][\p{L}\p{N}_$*?]*(\.[\p{L}_$*?][\p{L}\p{N}_$*?]*)*""")

    /** Words that can follow a class annotation in a class specification. */
    private val CLASS_SPEC_WORDS =
        setOf(
            "class",
            "interface",
            "enum",
            "public",
            "private",
            "protected",
            "final",
            "abstract",
            "static",
            "synthetic",
        )

    /** An option word: `-` and its letters, possibly none (a bare `-` is ProGuard's `-include`). */
    private val OPTION_PREFIX = Regex("^-[A-Za-z]*")

    /**
     * The options of [rules] outside [ALLOWED_OPTIONS], comments ignored, plus `@file` for an
     * include ([isClassAnnotation]), `quotes`, `non-ASCII` and `#` for characters refused outside
     * a comment. Tokens are split on whitespace and on `{ } ( ) ; ,` as ProGuard's word reader does, so
     * an option glued to a delimiter (`{ *; }-dontobfuscate`) is still seen. Some input is refused
     * rather than parsed, since the guard must split words exactly as ProGuard does:
     * - quotes: ProGuard ends a word at a quote, unquotes it, and stops honouring `#` and `{` inside
     *   one (`'a.B'-dontshrink`, `'-printconfiguration'`);
     * - anything but printable ASCII and tab: ProGuard reads the file as UTF-8 and splits on
     *   `Character.isWhitespace`, which also covers U+001C–U+001F and Unicode separators, so
     *   `a<U+001C>-dontshrink` is two words to ProGuard and one here. [rules] is decoded as
     *   ISO-8859-1, one char per byte, so a multi-byte character is caught by its bytes;
     * - a `#` glued to a word: after a file filter option (`-keepdirectories`, `-adapt*`) ProGuard
     *   reads file names, where `#` only starts a comment after whitespace — `a#b -dontobfuscate`
     *   is a filter then an option to ProGuard, a filter and a comment here.
     *
     * Comments stay free: ProGuard ignores them up to the end of the line, and both sides end a
     * line on `\n` or `\r` only. No library rule file checked uses quotes, non-ASCII code or a glued
     * `#`. A `-` followed by a digit is a value (`-assumevalues … return -1..5`).
     */
    fun disallowedOptions(rules: String): List<String> {
        val lines = rules.lines()
        val code = lines.map { it.substringBefore('#') }
        val found = LinkedHashSet(refusedCharacters(lines, code))
        val tokens = code.flatMap { line -> TOKEN.findAll(line).map { it.value } }
        var depth = 0
        var option: String? = null
        for ((index, token) in tokens.withIndex()) {
            when {
                token == "{" -> {
                    // Only a class specification opens a body. A file filter takes a bare `{` as a
                    // value (`-adaptresourcefilenames {`), after which ProGuard is back at top level.
                    if (depth == 0 && option !in CLASS_SPEC_OPTIONS) found += "{"
                    depth++
                }
                token == "}" -> depth = maxOf(0, depth - 1)
                token.startsWith("-") && token.getOrNull(1)?.isDigit() != true -> {
                    val word = OPTION_PREFIX.find(token)?.value.orEmpty()
                    if (word !in ALLOWED_OPTIONS) found += word
                    if (depth == 0) option = word
                }
                token.startsWith("@") && depth == 0 && token != "@interface" -> {
                    val previous = tokens.getOrNull(index - 1)
                    if (!isClassAnnotation(previous, token, tokens.getOrNull(index + 1))) found += "@file"
                }
            }
        }
        return found.toList()
    }

    /** The characters [disallowedOptions] refuses rather than parses, over [lines] and their [code]. */
    private fun refusedCharacters(
        lines: List<String>,
        code: List<String>,
    ): List<String> =
        buildList {
            if (lines.any { line -> line.indexOf('#').let { it > 0 && !line[it - 1].isWhitespace() } }) add("#")
            // A `#` inside quotes is no comment for ProGuard, but the quote before it is still caught here.
            if (code.any { '\'' in it || '"' in it }) add("quotes")
            if (code.any { line -> line.any { it != '\t' && it !in ' '..'~' } }) add("non-ASCII")
        }

    /**
     * Whether [token], an `@word` outside a class body, is an annotation: a name glued to the `@`
     * (wildcards allowed), then either what can only follow a class annotation, or — on a supertype
     * (`implements @a.Marker **`, [previous] being `extends` / `implements`) — a class name. Anything
     * else — a bare `@` (ProGuard reads it as a word of its own, so `@ x.pro` includes `x.pro`), or
     * `@x.pro` followed by an option or a path — is ProGuard's `@file` include.
     */
    private fun isClassAnnotation(
        previous: String?,
        token: String,
        next: String?,
    ): Boolean {
        if (!token.drop(1).matches(ANNOTATION_NAME) || next == null) return false
        if (previous == "extends" || previous == "implements") return next.matches(ANNOTATION_NAME)
        return next in CLASS_SPEC_WORDS || next.startsWith("@") || next.startsWith("!")
    }
}
