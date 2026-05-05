package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_macos_deco"

/**
 * JNI bridge to the macOS dialog/owner helper (`macos/decoration.m`, shipped as
 * `libnucleus_tao_macos_deco.dylib`).
 *
 * Mirrors the slice of [NativeTaoWindowsDecoBridge] used by `DecoratedDialog`
 * (owner relationship + window/screen geometry queries) so the dialog code path
 * is symmetric across platforms — the only difference is the native primitive
 * (`addChildWindow:` vs. `SetWindowLongPtrW(GWLP_HWNDPARENT)`).
 *
 * Every entry point must be invoked from the macOS main thread (= Tao
 * event-loop thread = Compose dispatcher thread). All [Long] handles are
 * NSView pointers, matching what [TaoWindow.nativeHandle] returns on macOS.
 */
internal object NativeTaoMacOsDecoBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoMacOsDecoBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Sets the owner of the window backing [childNsView] to the window backing
     * [ownerNsView] via `[parent addChildWindow:child ordered:NSWindowAbove]`.
     * The child stays above the owner in z-order, follows it across spaces and
     * minimisation, and disappears when the owner is closed. Pass `0` for
     * [ownerNsView] to clear the relationship.
     *
     * When [centerOnOwner] is true, the child is repositioned to the owner's
     * centre *before* `addChildWindow:` makes it visible — required because
     * AppKit shows the child at its current frame, which would otherwise be
     * Tao's default origin and produce a visible flash.
     *
     * Used by `DecoratedDialog` to mirror the non-modal owner semantics of an
     * AWT `JDialog` — the parent window stays interactive and keeps focus when
     * the dialog isn't key.
     */
    @JvmStatic
    external fun nativeSetOwner(childNsView: Long, ownerNsView: Long, centerOnOwner: Boolean)

    /**
     * Returns the NSWindow's outer frame as `[x, y, width, height]` in physical
     * screen pixels using a top-left origin (referenced to the primary
     * screen's top-left). Matches the Win32 `GetWindowRect` convention so the
     * Kotlin centring math is portable. Returns `null` if the view is not yet
     * attached to an NSWindow.
     */
    @JvmStatic
    external fun nativeGetWindowRect(nsView: Long): LongArray?

    /**
     * Returns the primary screen's `visibleFrame` (full screen minus menu bar
     * and Dock) as `[x, y, width, height]` in physical pixels with a top-left
     * origin. Used to resolve [androidx.compose.ui.window.WindowPosition.Aligned].
     */
    @JvmStatic
    external fun nativeGetPrimaryMonitorWorkArea(): LongArray?

    /**
     * Returns the primary screen's `backingScaleFactor` encoded as
     * `(scale * 1000)`. Used as a scale source while a Tao window's own scale
     * factor is not yet resolvable (the window object exists but the NSWindow
     * has not been created yet).
     */
    @JvmStatic
    external fun nativeGetPrimaryMonitorScaleMilli(): Int

    /**
     * Applies a content-area minimum size to the window backing [nsView] and
     * synchronously enlarges the NSWindow (preserving its top-left corner)
     * when the current content is smaller. Sizes are in **points** (logical
     * pixels); pass `0.0` (or negative) to leave a dimension unchanged.
     *
     * Bypasses Tao's UserEvent queue so [DecoratedWindow] can apply the
     * constraint inside `onWindowReady` *before* the position [LaunchedEffect]
     * runs — otherwise the centring math would key off the stale `state.size`
     * (e.g. `rememberWindowState()`'s 800×600 default) and centre the window
     * for a size that doesn't match what's about to land on screen.
     */
    @JvmStatic
    external fun nativeApplyContentMinSize(nsView: Long, widthPts: Double, heightPts: Double)
}
