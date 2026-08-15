@file:Suppress("TooGenericExceptionCaught")

package dev.nucleusframework.spellcheck

import dev.nucleusframework.spellcheck.linux.NativeSpellcheckBridge
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Isolated Hunspell session. Linux-only; a missing native library, missing
 * dictionary, or a non-Linux [osName] makes every operation a no-op
 * (`check`/`addToDictionary` return `false`, `suggest` returns empty).
 *
 * [SpellChecker] is the process-wide instance used by `nucleusApplication`.
 */
public class SpellcheckSession public constructor(
    locale: Locale = Locale.getDefault(),
    osName: String = System.getProperty("os.name", ""),
    dictionaryDirectories: List<Path> = DictionaryLocator.defaultDirectories(),
    userDictionaryFile: Path? = defaultUserDictionaryFile(locale),
) : AutoCloseable {
    private val lock = Any()
    private val userFile: Path? = userDictionaryFile
    private val checkCache = ConcurrentHashMap<String, Boolean>()
    private var handle: Long = 0L

    @Volatile
    private var available: Boolean = false

    /**
     * `true` when Hunspell is loaded and a matching system dictionary was found.
     * Lock-free; safe to read from the UI thread.
     */
    public val isAvailable: Boolean
        get() = available

    /**
     * Locale tag of the loaded dictionary (e.g. `en_US`), or `null` when
     * this session is a no-op.
     */
    public val dictionaryTag: String?

    init {
        val linux =
            osName.contains("Linux", ignoreCase = true) ||
                osName.contains("nux", ignoreCase = true)
        val files = if (linux) DictionaryLocator.find(locale, dictionaryDirectories) else null
        dictionaryTag = files?.tag
        if (linux && files != null) {
            handle = openHandle(files)
            if (handle != 0L) {
                loadUserDictionary()
                available = true
            }
        }
    }

    /**
     * Returns `true` when [word] is in the dictionary (or the user list).
     * Returns `false` for a misspelling **and** when this session is a no-op.
     */
    public fun check(word: String): Boolean {
        val normalized = normalize(word)
        if (normalized.isEmpty() || !available) return false
        checkCache[normalized]?.let { return it }
        return synchronized(lock) {
            checkCache[normalized]?.let { return it }
            val h = handle
            if (h == 0L) return@synchronized false
            val result =
                try {
                    NativeSpellcheckBridge.nativeSpell(h, normalized)
                } catch (e: Exception) {
                    logger.log(Level.FINE, "Hunspell check failed", e)
                    false
                } catch (e: UnsatisfiedLinkError) {
                    logger.log(Level.FINE, "Hunspell check failed", e)
                    false
                }
            checkCache[normalized] = result
            result
        }
    }

    /**
     * Suggestions for [word], empty when the word is correct or the session
     * is a no-op.
     */
    public fun suggest(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        return synchronized(lock) {
            val h = handle
            if (h == 0L) return@synchronized emptyList()
            try {
                NativeSpellcheckBridge
                    .nativeSuggest(h, normalize(word))
                    .filter { it.isNotEmpty() }
                    .distinct()
            } catch (e: UnsatisfiedLinkError) {
                logger.log(Level.FINE, "Hunspell suggest failed", e)
                emptyList()
            } catch (e: RuntimeException) {
                logger.log(Level.FINE, "Hunspell suggest failed", e)
                emptyList()
            }
        }
    }

    /**
     * Adds [word] to this session and persists it to the user dictionary file.
     * Returns `false` when the session is a no-op or the add fails.
     */
    public fun addToDictionary(word: String): Boolean {
        val normalized = normalize(word)
        if (normalized.isEmpty() || !available) return false
        val added =
            synchronized(lock) {
                val h = handle
                if (h == 0L) return@synchronized false
                try {
                    NativeSpellcheckBridge.nativeAdd(h, normalized)
                } catch (e: UnsatisfiedLinkError) {
                    logger.log(Level.FINE, "Hunspell add failed", e)
                    false
                } catch (e: RuntimeException) {
                    logger.log(Level.FINE, "Hunspell add failed", e)
                    false
                }
            }
        if (added) {
            checkCache[normalized] = true
            persistExecutor.execute { persistUserWord(normalized) }
        }
        return added
    }

    /**
     * Misspelled spans in [text] according to this session.
     */
    public fun misspellings(text: String): List<SpellcheckWord> =
        if (!isAvailable) emptyList() else misspellingRanges(text, ::check)

    override fun close() {
        available = false
        checkCache.clear()
        synchronized(lock) {
            val h = handle
            handle = 0L
            if (h != 0L) {
                try {
                    NativeSpellcheckBridge.nativeDestroy(h)
                } catch (e: UnsatisfiedLinkError) {
                    logger.log(Level.FINE, "Hunspell destroy failed", e)
                } catch (e: RuntimeException) {
                    logger.log(Level.FINE, "Hunspell destroy failed", e)
                }
            }
        }
    }

    private fun openHandle(files: DictionaryFiles): Long {
        if (!NativeSpellcheckBridge.isLoaded) return 0L
        return try {
            if (!NativeSpellcheckBridge.nativeIsHunspellPresent()) return 0L
            NativeSpellcheckBridge.nativeCreate(
                files.aff.toAbsolutePath().toString(),
                files.dic.toAbsolutePath().toString(),
            )
        } catch (e: UnsatisfiedLinkError) {
            logger.log(Level.WARNING, "Failed to create Hunspell handle", e)
            0L
        } catch (e: RuntimeException) {
            logger.log(Level.WARNING, "Failed to create Hunspell handle", e)
            0L
        }
    }

    private fun loadUserDictionary() {
        val file = userFile ?: return
        if (!Files.isRegularFile(file)) return
        try {
            Files.readAllLines(file).forEach { line ->
                val word = normalize(line)
                if (word.isNotEmpty() && handle != 0L) {
                    NativeSpellcheckBridge.nativeAdd(handle, word)
                }
            }
        } catch (e: java.io.IOException) {
            logger.log(Level.FINE, "Failed to load user dictionary $file", e)
        } catch (e: UnsatisfiedLinkError) {
            logger.log(Level.FINE, "Failed to load user dictionary $file", e)
        } catch (e: RuntimeException) {
            logger.log(Level.FINE, "Failed to load user dictionary $file", e)
        }
    }

    private fun persistUserWord(word: String) {
        val file = userFile ?: return
        try {
            val parent = file.parent
            if (parent != null) Files.createDirectories(parent)
            val existing =
                if (Files.isRegularFile(file)) {
                    Files.readAllLines(file).map { normalize(it) }.toHashSet()
                } else {
                    HashSet()
                }
            if (existing.add(word)) {
                Files.write(
                    file,
                    listOf(word),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            }
        } catch (e: java.io.IOException) {
            logger.log(Level.FINE, "Failed to persist user dictionary word", e)
        } catch (e: RuntimeException) {
            logger.log(Level.FINE, "Failed to persist user dictionary word", e)
        }
    }

    /** Factory helpers for the default user-dictionary path. */
    public companion object {
        private val logger: Logger = Logger.getLogger(SpellcheckSession::class.java.name)
        private val persistExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "nucleus-spellcheck-persist").apply { isDaemon = true }
            }

        /**
         * Default XDG path for persisted user-added words: `~/.config/nucleus/spellcheck/<tag>.dic`.
         */
        public fun defaultUserDictionaryFile(locale: Locale): Path {
            val tag =
                localeCandidatesTag(locale)
            val xdg =
                System
                    .getenv("XDG_CONFIG_HOME")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"), ".config")
            return xdg.resolve("nucleus").resolve("spellcheck").resolve("$tag.dic")
        }

        private fun localeCandidatesTag(locale: Locale): String = DictionaryLocator.localeCandidates(locale).first()

        private fun normalize(word: String): String = word.trim().replace('\u2019', '\'')
    }
}
