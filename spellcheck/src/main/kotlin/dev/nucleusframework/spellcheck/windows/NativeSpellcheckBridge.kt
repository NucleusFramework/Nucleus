package dev.nucleusframework.spellcheck.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_spellcheck"

internal object NativeSpellcheckBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeSpellcheckBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeIsAvailable(): Boolean

    @JvmStatic
    external fun nativeResolveLanguage(candidates: Array<String>): String?

    @JvmStatic
    external fun nativeCreate(language: String): Long

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
}
