package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.nucleusframework.window.tao.ffi.NativeTaoEglBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTextureBridge
import org.jetbrains.skia.DirectContext

/**
 * Linux: the EGL/Skia surface the enclosing Compose scene draws into. Consumed
 * by the `TextureView` composable, which must create its GL texture in the very
 * EGL context — and adopt it into the very Skia context — that will paint the
 * scene; a GPU image belongs to exactly one `DirectContext`.
 *
 * Like macOS (and unlike Windows' single shared ANGLE context), every Linux
 * surface owns a private EGL context and its own `DirectContext`, so each one
 * provides its own [LocalTaoGlTextureHost]: [TaoComposeSceneHostLinux] for the
 * window scene, [dev.nucleusframework.window.tao.popup.TaoPopupSceneLayerLinux]
 * for native popup layers,
 * [dev.nucleusframework.window.tao.popup.TaoStandalonePopupHostLinux] for tray
 * panels.
 *
 * Threading: everything here runs on the Tao event-loop thread. The context is
 * already current during composition and the draw pass (both happen inside
 * `ComposeScene.render()`, between the host's make-current/release), which is
 * exactly when imports are created and drawn; [withContextCurrent] binds it for
 * the teardown paths that run outside a render pass.
 */
internal interface TaoGlTextureHost {
    /** Skia context of this surface. */
    val directContext: DirectContext

    /**
     * Runs [block] with this surface's EGL context current, and returns its
     * result — or null when the context could not be bound (the surface is
     * being torn down, or another thread holds it). Skia GL deletes must never
     * run without a current context: the entry points Skia resolved through
     * `eglGetProcAddress` dereference thread-local driver state and crash.
     */
    fun <T> withContextCurrent(block: () -> T): T?
}

/**
 * Shared [TaoGlTextureHost.withContextCurrent] implementation. Fast path: the
 * context is already current (composition, draw pass, popup render) and the
 * block runs inline.
 *
 * The slow path binds — and then **restores whatever was current before**, not
 * merely unbinds. A disposal path for one surface can run inside another
 * surface's render pass: a popup's `TextureView` leaves the composition while
 * the parent window's scene is mid-render, with the window's context current.
 * Unbinding there would leave the thread with no context, and the rest of the
 * host frame (frame decoration, `flushAndSubmit`) would issue GL against
 * nothing — the Linux twin of the ANGLE surface-restore bug this feature fixed
 * on Windows.
 *
 * [attachment] must be read live from the owning surface: it is 0 (and this
 * returns null) once the surface detached, which is what keeps a late disposal
 * from dereferencing a freed `EglAttachment`.
 */
internal fun <T> withEglContextCurrent(
    attachment: Long,
    block: () -> T,
): T? {
    if (attachment == 0L || !NativeTaoLinuxTextureBridge.isLoaded) return null
    if (NativeTaoLinuxTextureBridge.nativeIsAttachmentCurrent(attachment)) return block()
    // Refused when a snapshot is already outstanding on this thread: rebinding
    // would then lose the outer binding, so give up instead (the caller treats
    // null as "context unavailable" and skips its GL work).
    if (!NativeTaoLinuxTextureBridge.nativeSaveCurrentBinding()) return null
    NativeTaoEglBridge.nativeMakeCurrent(attachment)
    if (!NativeTaoLinuxTextureBridge.nativeIsAttachmentCurrent(attachment)) {
        restoreDisplacedBinding(attachment)
        return null
    }
    return try {
        block()
    } finally {
        restoreDisplacedBinding(attachment)
    }
}

/** Puts back the binding [withEglContextCurrent] displaced, or unbinds if there was none. */
private fun restoreDisplacedBinding(attachment: Long) {
    if (!NativeTaoLinuxTextureBridge.nativeRestoreBinding()) {
        NativeTaoEglBridge.nativeReleaseCurrent(attachment)
    }
}

internal val LocalTaoGlTextureHost: ProvidableCompositionLocal<TaoGlTextureHost?> =
    compositionLocalOf { null }
