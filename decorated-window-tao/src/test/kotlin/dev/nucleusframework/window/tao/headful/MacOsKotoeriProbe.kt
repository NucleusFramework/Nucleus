package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Thin wrapper around the macOS headful helper that selects the real
 * Japanese Kotoeri IME and delivers AppKit `keyDown:` / `keyUp:` to TaoView.
 *
 * Stage-1 [dev.nucleusframework.window.tao.scene.TaoSceneImeTest] feeds
 * `TaoImeSession` directly. This probe goes through Kotoeri itself:
 * `TISSelectInputSource` → `[view keyDown:]` → `interpretKeyEvents:` →
 * `setMarkedText:` / `insertText:`.
 */
internal object MacOsKotoeriProbe {
    // Carbon `kVK_ANSI_*` / `kVK_Return` / `kVK_Space`.
    const val KEY_N: Int = 0x2D
    const val KEY_I: Int = 0x22
    const val KEY_H: Int = 0x04
    const val KEY_O: Int = 0x1F
    const val KEY_G: Int = 0x05
    const val KEY_SPACE: Int = 0x31
    const val KEY_RETURN: Int = 0x24

    fun isAvailable(): Boolean = NativeTaoBridge.nativeMacOsKotoeriAvailable()

    fun select(handle: Long): Boolean = NativeTaoBridge.nativeMacOsKotoeriSelect(handle)

    fun restore() = NativeTaoBridge.nativeMacOsKotoeriRestore()

    fun currentInputSource(): String = NativeTaoBridge.nativeMacOsCurrentInputSource()

    /** [autorepeat] marks the keyDown as a key repeat (held key). */
    fun postKey(
        handle: Long,
        keyCode: Int,
        characters: String,
        down: Boolean,
        autorepeat: Boolean = false,
    ): Boolean = NativeTaoBridge.nativeMacOsPostKeyToView(handle, keyCode, characters, down, autorepeat)
}
