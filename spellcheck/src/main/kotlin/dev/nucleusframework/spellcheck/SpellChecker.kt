package dev.nucleusframework.spellcheck

/**
 * Process-wide spell checker used by `nucleusApplication` text fields.
 *
 * On Linux this loads `libhunspell` via JNI and the locale-matching system
 * `.aff`/`.dic`. On macOS this uses `NSSpellChecker`. On Windows this uses
 * `ISpellChecker`. When the native library or dictionary is missing,
 * [check], [suggest] and [addToDictionary] are no-ops (`false` / empty,
 * never thrown).
 *
 * [session] constructs the native handle on first access (dictionary load).
 * The Tao installer calls it from a background dispatcher so the UI thread
 * never waits on the native engine.
 */
public object SpellChecker {
    @Volatile
    private var loaded: SpellcheckSession? = null
    private val loadLock = Any()

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
     * Loads (or returns) the process-wide session. Safe to call from a
     * background thread; not for the UI thread.
     */
    public fun ensureSession(): SpellcheckSession {
        loaded?.let { return it }
        return synchronized(loadLock) {
            loaded?.let { return it }
            SpellcheckSession().also { loaded = it }
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
