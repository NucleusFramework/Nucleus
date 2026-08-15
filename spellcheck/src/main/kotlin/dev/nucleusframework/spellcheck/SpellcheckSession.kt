@file:Suppress("TooGenericExceptionCaught")

package dev.nucleusframework.spellcheck

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger
import dev.nucleusframework.spellcheck.linux.NativeSpellcheckBridge as LinuxBridge
import dev.nucleusframework.spellcheck.macos.NativeSpellcheckBridge as MacBridge

/**
 * Isolated spellcheck session.
 *
 * Linux loads Hunspell via JNI and a locale-matching system `.aff`/`.dic`.
 * macOS uses [NSSpellChecker](https://developer.apple.com/documentation/appkit/nsspellchecker).
 * A missing native library, missing dictionary, or an unsupported [osName]
 * makes every operation a no-op (`check`/`addToDictionary` return `false`,
 * `suggest` returns empty).
 *
 * User-added words are persisted to [userDictionaryFile] (the Nucleus file,
 * not the OS learned-word list) so tests can isolate themselves with a temp
 * path.
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
    private var engine: Engine = Engine.None

    @Volatile
    private var available: Boolean = false

    /**
     * `true` when a native engine is loaded and a matching language/dictionary
     * was found. Lock-free; safe to read from the UI thread.
     */
    public val isAvailable: Boolean
        get() = available

    /**
     * Locale tag of the loaded dictionary (e.g. `en_US`), or `null` when
     * this session is a no-op.
     */
    public val dictionaryTag: String?

    init {
        engine = openEngine(locale, osName, dictionaryDirectories)
        dictionaryTag =
            when (val e = engine) {
                is Engine.Hunspell -> e.tag
                is Engine.MacOs -> e.language
                Engine.None -> null
            }
        if (engine !is Engine.None) {
            loadUserDictionary()
            available = true
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
            val result =
                try {
                    when (val e = engine) {
                        is Engine.Hunspell -> LinuxBridge.nativeSpell(e.handle, normalized)
                        is Engine.MacOs -> MacBridge.nativeSpell(e.documentTag, e.language, normalized)
                        Engine.None -> false
                    }
                } catch (e: Exception) {
                    logger.log(Level.FINE, "Spellcheck check failed", e)
                    false
                } catch (e: UnsatisfiedLinkError) {
                    logger.log(Level.FINE, "Spellcheck check failed", e)
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
            try {
                val raw =
                    when (val e = engine) {
                        is Engine.Hunspell -> LinuxBridge.nativeSuggest(e.handle, normalize(word))
                        is Engine.MacOs ->
                            MacBridge.nativeSuggest(e.documentTag, e.language, normalize(word))
                        Engine.None -> emptyArray()
                    }
                raw.filter { it.isNotEmpty() }.distinct()
            } catch (e: UnsatisfiedLinkError) {
                logger.log(Level.FINE, "Spellcheck suggest failed", e)
                emptyList()
            } catch (e: RuntimeException) {
                logger.log(Level.FINE, "Spellcheck suggest failed", e)
                emptyList()
            }
        }
    }

    /**
     * Adds [word] to this session and persists it to the user dictionary file.
     * Returns `false` when the session is a no-op or the add fails.
     *
     * On Linux the word is also injected into the Hunspell handle. On macOS it
     * lives in the process cache + user file only — `NSSpellChecker.learnWord`
     * is not called, so the OS learned-word list is left untouched.
     */
    public fun addToDictionary(word: String): Boolean {
        val normalized = normalize(word)
        if (normalized.isEmpty() || !available) return false
        val added =
            synchronized(lock) {
                try {
                    when (val e = engine) {
                        is Engine.Hunspell -> LinuxBridge.nativeAdd(e.handle, normalized)
                        is Engine.MacOs -> true
                        Engine.None -> false
                    }
                } catch (e: UnsatisfiedLinkError) {
                    logger.log(Level.FINE, "Spellcheck add failed", e)
                    false
                } catch (e: RuntimeException) {
                    logger.log(Level.FINE, "Spellcheck add failed", e)
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
            val current = engine
            engine = Engine.None
            try {
                when (current) {
                    is Engine.Hunspell -> LinuxBridge.nativeDestroy(current.handle)
                    is Engine.MacOs -> MacBridge.nativeDestroyDocument(current.documentTag)
                    Engine.None -> Unit
                }
            } catch (e: UnsatisfiedLinkError) {
                logger.log(Level.FINE, "Spellcheck destroy failed", e)
            } catch (e: RuntimeException) {
                logger.log(Level.FINE, "Spellcheck destroy failed", e)
            }
        }
    }

    private fun openEngine(
        locale: Locale,
        osName: String,
        dictionaryDirectories: List<Path>,
    ): Engine =
        when {
            isLinux(osName) -> openHunspell(locale, dictionaryDirectories)
            isMacOs(osName) -> openMacOs(locale)
            else -> Engine.None
        }

    private fun openHunspell(
        locale: Locale,
        dictionaryDirectories: List<Path>,
    ): Engine {
        val files = DictionaryLocator.find(locale, dictionaryDirectories) ?: return Engine.None
        if (!LinuxBridge.isLoaded) return Engine.None
        return try {
            if (!LinuxBridge.nativeIsHunspellPresent()) return Engine.None
            val handle =
                LinuxBridge.nativeCreate(
                    files.aff.toAbsolutePath().toString(),
                    files.dic.toAbsolutePath().toString(),
                )
            if (handle == 0L) Engine.None else Engine.Hunspell(handle, files.tag)
        } catch (e: UnsatisfiedLinkError) {
            logger.log(Level.WARNING, "Failed to create Hunspell handle", e)
            Engine.None
        } catch (e: RuntimeException) {
            logger.log(Level.WARNING, "Failed to create Hunspell handle", e)
            Engine.None
        }
    }

    private fun openMacOs(locale: Locale): Engine {
        if (!MacBridge.isLoaded) return Engine.None
        return try {
            if (!MacBridge.nativeIsAvailable()) return Engine.None
            val candidates = DictionaryLocator.localeCandidates(locale).toTypedArray()
            val language = MacBridge.nativeResolveLanguage(candidates) ?: return Engine.None
            Engine.MacOs(MacBridge.nativeCreateDocument(), language)
        } catch (e: UnsatisfiedLinkError) {
            logger.log(Level.WARNING, "Failed to create NSSpellChecker session", e)
            Engine.None
        } catch (e: RuntimeException) {
            logger.log(Level.WARNING, "Failed to create NSSpellChecker session", e)
            Engine.None
        }
    }

    private fun loadUserDictionary() {
        val file = userFile ?: return
        if (!Files.isRegularFile(file)) return
        try {
            Files.readAllLines(file).forEach { line ->
                val word = normalize(line)
                if (word.isEmpty()) return@forEach
                when (val e = engine) {
                    is Engine.Hunspell -> LinuxBridge.nativeAdd(e.handle, word)
                    is Engine.MacOs, Engine.None -> Unit
                }
                checkCache[word] = true
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

    private sealed class Engine {
        class Hunspell(
            val handle: Long,
            val tag: String,
        ) : Engine()

        class MacOs(
            val documentTag: Long,
            val language: String,
        ) : Engine()

        data object None : Engine()
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

        private fun isLinux(osName: String): Boolean =
            osName.contains("Linux", ignoreCase = true) ||
                osName.contains("nux", ignoreCase = true)

        private fun isMacOs(osName: String): Boolean =
            osName.contains("Mac", ignoreCase = true) ||
                osName.contains("Darwin", ignoreCase = true)
    }
}
