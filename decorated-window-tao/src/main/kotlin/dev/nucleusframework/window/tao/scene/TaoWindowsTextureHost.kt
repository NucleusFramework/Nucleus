package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.nucleusframework.window.tao.ffi.NativeTaoTextureBridge
import org.jetbrains.skia.DirectContext

/**
 * Windows: the ANGLE/Skia surface the enclosing Compose scene draws into.
 * Consumed by the `TextureView` composable, which must adopt its imported GL
 * texture into the very Skia context that will paint the scene — a GPU image
 * belongs to exactly one `DirectContext`.
 *
 * Windows keeps one `EGLContext` per *surface owner*, not one per process: each
 * window host registers its own on `nativeAttach` (keyed by HWND), overlay and
 * popup layers borrow their parent host's, and ownerless tray panels bind the
 * immortal headless one. [hostHwnd] is what picks the right trio, so the import
 * needs no context switch of its own. The Skia context follows the same split:
 * a window scene ([TaoComposeSceneHostWindows]) and a standalone tray panel
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

    /**
     * Announces that GL state on *this surface's* EGL context just changed
     * behind Skia's back: importing a texture binds the producer's pbuffer
     * current and `eglBindTexImage`s onto a fresh texture id, destroying it
     * releases that binding — the same protocol the host applies to
     * overlay/popup renderers, which also bind their own surface on it between
     * frames.
     *
     * Reset immediately rather than flagged for the next frame entry (the
     * overlay path's timing): import and destroy run from *inside*
     * `ComposeScene.render()`, so the stale cache would be consumed by the
     * `flushAndSubmit` of the very frame that triggered them.
     */
    fun markGlStateDirtied() {
        directContext.resetGLAll()
    }
}

/**
 * Runs [block] and puts back the EGL binding it displaced — for code that binds
 * a surface of its own (a standalone tray panel's bring-up, render and
 * teardown) from a path that can run inside **another** surface's render pass.
 *
 * The Windows counterpart of Linux' `preservingEglBinding`. A
 * `TaoStandalonePopup` composed into a live window builds its host from
 * `remember {}`, sizes it from the caller's layout and disposes it from
 * `onDispose` — all inside the window scene's `ComposeScene.render()`. Leaving
 * the panel's headless context and 1x1 pbuffer current there sends the
 * remainder of that frame — `applyFrameDecoration`, glyph-atlas uploads,
 * `flushAndSubmit` — into the panel instead of the window.
 *
 * When nothing was current beforehand the thread is left unbound: the surface
 * [block] bound is the caller's own, and the next unrelated GL work on this
 * thread — a window host's frame, an overlay renderer — must not inherit it.
 * Every such consumer binds its own surface at entry, so unbinding costs
 * nothing; leaving a 1x1 panel pbuffer current costs a corrupted frame.
 */
internal fun <T> preservingAngleBinding(block: () -> T): T {
    if (!NativeTaoTextureBridge.isLoaded) return block()
    // Refused when a snapshot is already outstanding on this thread; the outer
    // scope then restores the binding when it unwinds.
    if (!NativeTaoTextureBridge.nativeSaveCurrentBinding()) return block()
    return try {
        block()
    } finally {
        NativeTaoTextureBridge.nativeRestoreBinding()
    }
}

internal val LocalTaoWindowsTextureHost: ProvidableCompositionLocal<TaoWindowsTextureHost?> =
    compositionLocalOf { null }
