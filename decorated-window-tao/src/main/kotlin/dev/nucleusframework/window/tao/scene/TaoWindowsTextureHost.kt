package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import org.jetbrains.skia.DirectContext

/**
 * Windows: the ANGLE/Skia surface the enclosing Compose scene draws into.
 * Consumed by the `TextureView` composable, which must adopt its imported GL
 * texture into the very Skia context that will paint the scene — a GPU image
 * belongs to exactly one `DirectContext`.
 *
 * Unlike macOS and Linux, every Windows surface shares one process-wide ANGLE
 * `EGLContext`, so the import itself needs no per-surface context switch. The
 * *Skia* context is not shared, though: a window scene
 * ([TaoComposeSceneHostWindows]) and a standalone tray panel
 * ([dev.nucleusframework.window.tao.popup.TaoStandalonePopupHost]) each build
 * their own, so each provides its own [LocalTaoWindowsTextureHost]. Overlay and
 * popup layers render through the host scene's context and simply inherit it.
 *
 * This is deliberately narrower than [dev.nucleusframework.window.tao.popup.TaoPopupHostWindows]:
 * the tray panel is not a popup host and cannot implement that surface, but it
 * can host a `TextureView`.
 *
 * Threading: everything here runs on the Tao event-loop thread.
 */
internal interface TaoWindowsTextureHost {
    /**
     * HWND whose EGL trio the import resolves against, or 0 when the surface has
     * none of its own (the tray panel renders through the process-wide headless
     * context, which the native import falls back to).
     */
    val hostHwnd: Long

    /** Skia context of this surface. */
    val directContext: DirectContext

    /**
     * Schedules another frame. Used when a keyed-mutex staging copy is skipped
     * because the producer held the mutex past the timeout: without a retry the
     * stale frame would stay on screen until something else invalidates.
     */
    fun requestRedraw()
}

internal val LocalTaoWindowsTextureHost: ProvidableCompositionLocal<TaoWindowsTextureHost?> =
    compositionLocalOf { null }
