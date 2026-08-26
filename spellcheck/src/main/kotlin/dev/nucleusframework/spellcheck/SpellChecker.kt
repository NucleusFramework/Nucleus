package dev.nucleusframework.spellcheck

import java.util.Locale

/**
 * Process-wide spell checker used by `nucleusApplication` text fields.
 *
 * On Linux this loads `libhunspell` via JNI and the locale-matching system
 * `.aff`/`.dic`. On macOS this uses `NSSpellChecker`. On Windows this uses
 * `ISpellChecker`. When the native library or dictionary is missing,
 * [check], [suggest] and [addToDictionary] are no-ops (`false` / empty,
 * never thrown).
 *
 * The language is a [Locale]. It defaults to [Locale.getDefault] — the same
 * value `nucleusApplication(defaultLocale = …)` writes — and can be overridden
 * without touching the JVM default:
 *
 * ```
 * SpellChecker.locale = Locale.FRANCE
 * SpellChecker.locale = Locale.forLanguageTag("de-DE")
 * ```
 *
 * [session] constructs the native handle on first access (dictionary load).
 * The Tao installer calls it from a background dispatcher so the UI thread
 * never waits on the native engine.
 */
public object SpellChecker {
    @Volatile
    private var loaded: SpellcheckSession? = null

    @Volatile
    private var configuredLocale: Locale? = null
    private val extras = HashMap<Locale, SpellcheckSession>()
    private val loadLock = Any()

    /**
     * Language of the process-wide session.
     *
     * Defaults to [Locale.getDefault] until assigned. Setting a new value
     * drops the current process-wide session so the next [ensureSession]
     * loads that language. Sessions requested for other locales via
     * [ensureSession] stay cached.
     */
    public var locale: Locale
        get() = configuredLocale ?: Locale.getDefault()
        set(value) {
            synchronized(loadLock) {
                val previousConfigured = configuredLocale
                configuredLocale = value
                if (previousConfigured == value && loaded != null) return
                if (loaded?.locale == value) return
                val previous = loaded
                loaded = extras.remove(value)
                previous?.close()
            }
        }

    /**
     * Process-wide session. First access loads the native engine; prefer
     * [sessionIfReady] on the UI thread.
     */
    public val session: SpellcheckSession
        get() = ensureSession()

    /**
     * Already-loaded session, or `null` if [ensureSession] has not finished.
     * Never blocks.
     */
    public val sessionIfReady: SpellcheckSession?
        get() = loaded

    /**
     * `true` when the process-wide session is loaded and has an engine.
     * Never blocks and never starts a load.
     */
    public val isAvailable: Boolean
        get() = loaded?.isAvailable == true

    /**
     * Loads (or returns) the process-wide session for [locale]. Safe to call
     * from a background thread; not for the UI thread.
     */
    public fun ensureSession(): SpellcheckSession = ensureSession(locale)

    /**
     * Loads (or returns) a session for [locale].
     *
     * The process-wide [locale] uses the singleton session. Any other value
     * is cached separately so a field can override the language without
     * replacing the default.
     */
    public fun ensureSession(locale: Locale): SpellcheckSession {
        if (locale == this.locale) {
            loaded?.let { return it }
        } else {
            synchronized(loadLock) {
                extras[locale]?.let { return it }
            }
        }
        return synchronized(loadLock) {
            sessionLocked(locale)
        }
    }

    private fun sessionLocked(locale: Locale): SpellcheckSession {
        if (locale == this.locale) {
            loaded?.let { return it }
            if (configuredLocale == null) {
                configuredLocale = locale
            }
            return SpellcheckSession(locale = locale).also { loaded = it }
        }
        extras[locale]?.let { return it }
        return SpellcheckSession(locale = locale).also { extras[locale] = it }
    }

    internal fun resetForTests() {
        synchronized(loadLock) {
            loaded?.close()
            loaded = null
            extras.values.forEach { it.close() }
            extras.clear()
            configuredLocale = null
        }
    }

    /**
     * Returns `true` when [word] is in the dictionary.
     * Returns `false` for a misspelling and when spellcheck is a no-op.
     * Never starts a load.
     */
    public fun check(word: String): Boolean = loaded?.check(word) ?: false

    /**
     * Suggestions for [word], or an empty list when the session is a no-op.
     * Never starts a load.
     */
    public fun suggest(word: String): List<String> = loaded?.suggest(word) ?: emptyList()

    /**
     * Adds [word] to the process-wide user dictionary.
     * Returns `false` when the session is a no-op. Never starts a load.
     */
    public fun addToDictionary(word: String): Boolean = loaded?.addToDictionary(word) ?: false

    /**
     * Misspelled spans in [text] according to the process-wide session.
     */
    public fun misspellings(text: String): List<SpellcheckWord> = loaded?.misspellings(text) ?: emptyList()
}
