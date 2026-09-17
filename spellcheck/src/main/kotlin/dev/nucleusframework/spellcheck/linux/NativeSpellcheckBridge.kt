package dev.nucleusframework.spellcheck.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_spellcheck"

internal object NativeSpellcheckBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeSpellcheckBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeIsHunspellPresent(): Boolean

    @JvmStatic
    external fun nativeCreate(
        affPath: String,
        dicPath: String,
    ): Long

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    @JvmStatic
    external fun nativeSpell(
        handle: Long,
        word: String,
    ): Boolean

    @JvmStatic
    external fun nativeSuggest(
        handle: Long,
        word: String,
    ): Array<String>

    @JvmStatic
    external fun nativeAdd(
        handle: Long,
        word: String,
    ): Boolean
}
