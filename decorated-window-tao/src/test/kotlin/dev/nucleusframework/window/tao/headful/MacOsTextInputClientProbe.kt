package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Headful e2e helper: call TaoView's `NSTextInputClient` the way IMKit does.
 *
 * Used to lock the answers that Kotoeri / ATOK cross-check during live
 * conversion (reporter follow-up on #595): `markedRange`, `selectedRange`,
 * `attributedSubstringForProposedRange`, `characterIndexForPoint`, plus
 * direct `setMarkedText:` / `insertText:` injection.
 */
internal object MacOsTextInputClientProbe {
    const val NS_NOT_FOUND: Long = -1L

    fun query(handle: Long): Snapshot {
        val ranges = LongArray(5)
        val substring = NativeTaoBridge.nativeMacOsQueryTextInputClient(handle, ranges)
        return Snapshot(
            markedLocation = ranges[0],
            markedLength = ranges[1],
            selectedLocation = ranges[2],
            selectedLength = ranges[3],
            characterIndex = ranges[4],
            substring = substring,
        )
    }

    fun setMarkedText(
        handle: Long,
        text: String,
        selectedLocation: Int,
        selectedLength: Int,
    ): Boolean =
        NativeTaoBridge.nativeMacOsInjectMarkedText(
            handle,
            text,
            selectedLocation,
            selectedLength,
        )

    /**
     * A negative [replacementLocation] injects `{NSNotFound, 0}` (ordinary
     * typing); a non-negative one replays the accent-picker replacement
     * commit (UTF-16 document-absolute range).
     */
    fun insertText(
        handle: Long,
        text: String,
        replacementLocation: Long = -1L,
        replacementLength: Long = 0L,
    ): Boolean =
        NativeTaoBridge.nativeMacOsInjectInsertText(
            handle,
            text,
            replacementLocation,
            replacementLength,
        )

    data class Snapshot(
        val markedLocation: Long,
        val markedLength: Long,
        val selectedLocation: Long,
        val selectedLength: Long,
        val characterIndex: Long,
        val substring: String,
    )
}
