package dev.nucleusframework.desktop.application.internal

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Keep rules that libraries ship inside their JARs, applied to the ProGuard pass: rule files under
 * [DIRECTORY], plus the providers their `META-INF/services` files declare ([ServiceProviderRules]).
 *
 * R8 reads them on its own (`EmbeddedRulesExtractor`, `--classfile` included) and AGP hands them to
 * the shrinker, but standalone ProGuard never looks inside its `-injars` (Guardsquare/proguard#337),
 * so a desktop release silently lost every rule coroutines, serialization, OkHttp, Ktor… ship.
 *
 * Only the legacy location [DIRECTORY] is read, files directly inside it, any name (AGP's rule and
 * the documented layout). `META-INF/com.android.tools/` is ignored: its `proguard` variants only
 * repeat the legacy files with comment changes, and its `r8*` variants use R8-only syntax that
 * ProGuard rejects (`kotlinx-serialization-r8.pro`: `-keep,allowaccessmodification`).
 *
 * Extracted files are named by the plugin, never after the entry, so a hostile entry name cannot
 * escape the destination directory. A rule file may only say what to keep: one using any option
 * outside [ALLOWED_OPTIONS] — spelled in full, since ProGuard also accepts any prefix of an option
 * (`-incl`, a bare `-`) — or the `@file` include is skipped with a warning. A deny-list would always
 * miss one: whole-build switches, file and keystore options, aliases (`-defaultpackage`).
 */
internal object ConsumerProguardRules {
    const val DIRECTORY = "META-INF/proguard/"

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

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
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
    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    private const val MAX_NAME_LENGTH = 80

    /** A rule file left out of the pass, and why. */
    data class Skipped(
        val jar: File,
        val entry: String,
        val reason: String,
    )

    /** What [extract] wrote ([files], in classpath order, the service rules last) and what it left out. */
    data class Result(
        val files: List<File>,
        val skipped: List<Skipped>,
    )

    /**
     * Extracts the rule files of [jars] into [destinationDir], in [jars] order. JARs for which
     * [isExcluded] answers `true` contribute no rule file — their `META-INF/services` providers are
     * still kept: those rules are generated here, and dropping them would silently lose a JDBC
     * driver or a logging binding. A file whose content was already extracted (shaded JARs embed
     * copies of their dependencies' rules) is written once.
     */
    fun extract(
        jars: List<File>,
        destinationDir: File,
        isExcluded: (File) -> Boolean = { false },
    ): Result {
        destinationDir.mkdirs()
        val files = mutableListOf<File>()
        val skipped = mutableListOf<Skipped>()
        val seen = HashSet<String>()
        val providers = LinkedHashMap<String, MutableSet<String>>()
        for (jar in jars.filter { it.isFile }) {
            val (ruleEntries, serviceEntries) = readEntries(jar, skipped).partition { it.first.startsWith(DIRECTORY) }
            for ((entry, bytes) in serviceEntries) {
                val service = entry.removePrefix(ServiceProviderRules.DIRECTORY)
                val names = ServiceProviderRules.providers(bytes.withoutBom().toString(Charsets.UTF_8))
                val invalid = ServiceProviderRules.invalidName(service, names)
                when {
                    invalid != null -> skipped += Skipped(jar, entry, "not a class name: $invalid")
                    ServiceProviderRules.isRuntimeService(service) ->
                        providers.getOrPut(service) { LinkedHashSet() } += names
                }
            }
            if (isExcluded(jar)) continue
            for ((entry, bytes) in ruleEntries) {
                val content = bytes.withoutBom()
                val rejected = disallowedOptions(content.toString(Charsets.ISO_8859_1))
                when {
                    rejected.isNotEmpty() -> skipped += Skipped(jar, entry, "uses ${rejected.joinToString()}")
                    seen.add(content.sha256()) -> {
                        val baseName = entry.substringAfterLast('/').removeSuffix(".pro")
                        val name = "%03d-%s".format(files.size, safeName("${jar.nameWithoutExtension}-$baseName"))
                        files += destinationDir.resolve(name).apply { writeBytes(content) }
                    }
                }
            }
        }
        if (providers.isNotEmpty()) {
            val rules = ServiceProviderRules.rules(providers, ServiceProviderRules.programClasses(jars))
            files += destinationDir.resolve("%03d-service-providers.pro".format(files.size)).apply { writeText(rules) }
        }
        return Result(files, skipped)
    }

    /**
     * The options of [rules] outside [ALLOWED_OPTIONS], comments ignored, plus `@file` for an
     * include ([isClassAnnotation]), `quotes` and `non-ASCII` for characters refused outside a
     * comment. Tokens are split on whitespace and on `{ } ( ) ; ,` as ProGuard's word reader does, so
     * an option glued to a delimiter (`{ *; }-dontobfuscate`) is still seen. Some input is refused
     * rather than parsed, since the guard must split words exactly as ProGuard does:
     * - quotes: ProGuard ends a word at a quote, unquotes it, and stops honouring `#` and `{` inside
     *   one (`'a.B'-dontshrink`, `'-printconfiguration'`);
     * - anything but printable ASCII and tab: ProGuard reads the file as UTF-8 and splits on
     *   `Character.isWhitespace`, which also covers U+001C–U+001F and Unicode separators, so
     *   `a<U+001C>-dontshrink` is two words to ProGuard and one here. [rules] is decoded as
     *   ISO-8859-1, one char per byte, so a multi-byte character is caught by its bytes.
     *
     * Comments stay free: ProGuard ignores them up to the end of the line, and both sides end a
     * line on `\n` or `\r` only. No library rule file checked uses quotes or non-ASCII code. A `-`
     * followed by a digit is a value (`-assumevalues … return -1..5`).
     */
    fun disallowedOptions(rules: String): List<String> {
        val code = rules.lineSequence().map { it.substringBefore('#') }.toList()
        val found = LinkedHashSet<String>()
        // A `#` inside quotes is no comment for ProGuard, but the quote before it is still caught here.
        if (code.any { '\'' in it || '"' in it }) found += "quotes"
        if (code.any { line -> line.any { it != '\t' && it !in ' '..'~' } }) found += "non-ASCII"
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

    /**
     * `group:module` of a resolved module, or the project path (`:shared`) of a project dependency;
     * `null` for anything else (a file dependency).
     */
    fun coordinateOf(id: ComponentIdentifier): String? =
        when (id) {
            is ModuleComponentIdentifier -> "${id.group}:${id.module}"
            is ProjectComponentIdentifier -> id.projectPath
            else -> null
        }

    /**
     * Whether [coordinate] matches one of [exclusions]: `group:module` or a project path, `*`
     * matching any run of characters (`com.squareup.*:*`, `*:okhttp`).
     */
    fun isExcluded(
        coordinate: String?,
        exclusions: Collection<String>,
    ): Boolean =
        coordinate != null &&
            exclusions.any { pattern ->
                pattern
                    .trim()
                    .split('*')
                    .joinToString(".*") { Regex.escape(it) }
                    .toRegex()
                    .matches(coordinate)
            }

    private fun readEntries(
        jar: File,
        skipped: MutableList<Skipped>,
    ): List<Pair<String, ByteArray>> =
        try {
            ZipFile(jar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.isRuleOrServiceEntry() }
                    .sortedBy { it.name }
                    .map { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
                    .toList()
            }
        } catch (e: ZipException) {
            skipped += Skipped(jar, DIRECTORY, "unreadable JAR: ${e.message}")
            emptyList()
        }

    private fun String.isRuleOrServiceEntry(): Boolean =
        isDirectEntryOf(DIRECTORY) || isDirectEntryOf(ServiceProviderRules.DIRECTORY)

    private fun String.isDirectEntryOf(directory: String): Boolean =
        startsWith(directory) && length > directory.length && indexOf('/', directory.length) < 0

    private fun ByteArray.withoutBom(): ByteArray {
        val hasBom = size >= UTF8_BOM.size && UTF8_BOM.indices.all { this[it] == UTF8_BOM[it] }
        return if (hasBom) copyOfRange(UTF8_BOM.size, size) else this
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private fun safeName(name: String): String = name.replace(UNSAFE_NAME_CHARS, "_").take(MAX_NAME_LENGTH) + ".pro"
}
