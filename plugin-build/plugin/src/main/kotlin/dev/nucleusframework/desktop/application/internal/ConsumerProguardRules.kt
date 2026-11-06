package dev.nucleusframework.desktop.application.internal

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.io.File
import java.security.MessageDigest
import java.util.Locale
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
 * escape the destination directory. A rule file may only say what to keep: one [ConsumerRuleGuard]
 * refuses is skipped with a warning.
 */
internal object ConsumerProguardRules {
    const val DIRECTORY = "META-INF/proguard/"

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

    /** Platform module suffixes of the JVM variant of a Kotlin Multiplatform library ("" = as is). */
    private val PLATFORM_SUFFIXES = listOf("", "-jvm", "-desktop")
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
                // A services file that is no provider list (Groovy's ExtensionModule is properties)
                // is left alone, silently: nothing from it reaches the rules, so nothing is lost.
                val providerList = ServiceProviderRules.invalidName(service, names) == null
                if (providerList && ServiceProviderRules.isRuntimeService(service)) {
                    providers.getOrPut(service) { LinkedHashSet() } += names
                }
            }
            if (isExcluded(jar)) continue
            for ((entry, bytes) in ruleEntries) {
                val content = bytes.withoutBom()
                val rejected = ConsumerRuleGuard.disallowedOptions(content.toString(Charsets.ISO_8859_1))
                when {
                    rejected.isNotEmpty() -> skipped += Skipped(jar, entry, "uses ${rejected.joinToString()}")
                    seen.add(content.sha256()) -> {
                        val baseName = entry.substringAfterLast('/').removeSuffix(".pro")
                        val name =
                            "%03d-%s".format(Locale.ROOT, files.size, safeName("${jar.nameWithoutExtension}-$baseName"))
                        files += destinationDir.resolve(name).apply { writeBytes(content) }
                    }
                }
            }
        }
        if (providers.isNotEmpty()) {
            val rules = ServiceProviderRules.rules(providers, ServiceProviderRules.programClasses(jars))
            val name = "%03d-service-providers.pro".format(Locale.ROOT, files.size)
            files += destinationDir.resolve(name).apply { writeText(rules) }
        }
        return Result(files, skipped)
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

    /** Whether [coordinate] matches one of [exclusions] ([matchingExclusions]). */
    fun isExcluded(
        coordinate: String?,
        exclusions: Collection<String>,
    ): Boolean = matchingExclusions(coordinate, exclusions).isNotEmpty()

    /**
     * The [exclusions] matching [coordinate]: `group:module` or a project path, `*` matching any run
     * of characters (`com.squareup.*:*`, `*:okhttp`). A Kotlin Multiplatform library resolves to its
     * platform module (`kotlinx-coroutines-core` → `kotlinx-coroutines-core-jvm`), so the module as
     * declared in `dependencies { }` — without its `-jvm` / `-desktop` suffix — matches too.
     */
    fun matchingExclusions(
        coordinate: String?,
        exclusions: Collection<String>,
    ): List<String> {
        if (coordinate == null) return emptyList()
        val candidates = PLATFORM_SUFFIXES.map { coordinate.removeSuffix(it) }.toSet()
        return exclusions.filter { pattern ->
            val regex = pattern.trim().split('*').joinToString(".*") { Regex.escape(it) }.toRegex()
            candidates.any { regex.matches(it) }
        }
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
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun safeName(name: String): String = name.replace(UNSAFE_NAME_CHARS, "_").take(MAX_NAME_LENGTH) + ".pro"
}
