package dev.nucleusframework.spellcheck.macos

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
    external fun nativeCreateDocument(): Long

    @JvmStatic
    external fun nativeDestroyDocument(tag: Long)

    @JvmStatic
    external fun nativeSpell(
        tag: Long,
        language: String,
        word: String,
    ): Boolean

    @JvmStatic
    external fun nativeSuggest(
        tag: Long,
        language: String,
        word: String,
    ): Array<String>
}
