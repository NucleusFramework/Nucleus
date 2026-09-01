package dev.nucleusframework.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.LocalBackdropComposeTint
import dev.nucleusframework.window.tao.LocalRequestedTransparentBackground
import dev.nucleusframework.window.tao.LocalWindowClearColorLayers
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge

/**
 * Applies a Windows 11 system backdrop to this window: DWM composites the
 * material behind the window and it shows wherever the app paints nothing.
 *
 * The client area is turned transparent for as long as this is applied, so the
 * content decides how much of the material comes through: a full-bleed opaque
 * background covers all of it, whereas leaving the window — or just a panel's
 * margins — unpainted reveals it. An app that wants a visible backdrop has to
 * stop painting somewhere.
 *
 * [WindowBackground] keeps working alongside this and does not need removing:
 * its colour is suspended while the backdrop is active and restored when it
 * goes, which is what makes a backdrop toggleable at runtime.
 *
 * The material follows the OS light/dark setting on its own.
 *
 * Older Windows versions degrade rather than no-op, because the documented
 * `DWMWA_SYSTEMBACKDROP_TYPE` only exists from Windows 11 22H2:
 *
 * - **Windows 11 before 22H2** — [Mica][WindowsBackdropStyle.Mica] and
 *   [MicaAlt][WindowsBackdropStyle.MicaAlt] use the older single-material
 *   Mica attribute; they become indistinguishable there.
 * - **Windows 10** — no Mica exists at all, so every style degrades to
 *   acrylic, which blurs what is *behind* the window rather than tinting from
 *   the wallpaper. Expect a different look, not the same one.
 *
 * Both fallbacks rely on undocumented APIs and are skipped if unavailable, in
 * which case the window simply stays opaque.
 *
 * [tint] is the app's tint layer over the material — the Fluent acrylic
 * recipe is blur + tint + noise, and the tint belongs to the app. Its alpha
 * decides how much material survives: the lower, the prettier and the less
 * readable. Left [unspecified][Color.Unspecified], Mica and Mica Alt get no
 * tint (they already follow the window's light/dark), while [Acrylic
 * gets][WindowsBackdropStyle.Acrylic] the window background at a moderate
 * opacity — DWM's own acrylic tint is a generic system grey, unrelated to the
 * app's palette, and untinted it reads as a foreign surface inside a themed
 * app. On the Windows 10 fallback the tint rides the accent policy instead.
 *
 * A silent no-op on macOS and Linux; macOS has a per-region equivalent instead
 * (`Modifier.windowGlassRegion`), and Linux has no equivalent at all.
 *
 * The window reverts to its opaque background when the composable leaves the
 * composition.
 */
@Suppress("FunctionNaming")
@Composable
public fun DecoratedWindowScope.WindowsBackdrop(
    style: WindowsBackdropStyle,
    tint: Color = Color.Unspecified,
    tier: WindowsBackdropTier = WindowsBackdropTier.Auto,
) {
    // Tao always provides a [TaoDecoratedWindowScope] at runtime — same
    // contract as `BasicTitleBar`.
    val taoWindow = (this as TaoDecoratedWindowScope).window
    val transparencyState = LocalRequestedTransparentBackground.current
    val composeTintState = LocalBackdropComposeTint.current
    // The tint is deliberately NOT a key: a theme-driven tint change must
    // re-tint in place, not tear the whole native backdrop down and up (a
    // visible opaque blink). Accent-tier tint changes are re-applied through
    // updateTint below instead.
    var holder by remember { mutableStateOf<WindowsBackdropMode.Holder?>(null) }
    DisposableEffect(taoWindow, style, tier) {
        holder = WindowsBackdropMode.acquire(taoWindow, style, tint, tier, transparencyState, composeTintState)
        onDispose {
            // Hand the window back to its opaque background — or to the next
            // surviving holder's backdrop: a forced backdrop must not outlive
            // its call site, and a released one must not take a still-composed
            // holder's material with it.
            holder?.let { WindowsBackdropMode.release(taoWindow, it) }
            holder = null
        }
    }

    // The Compose-side tint layer over the DWM material. Mica themes itself
    // from the window's dark-mode flag, but Acrylic's DWM tint is a generic
    // system grey — unrelated to the app's palette — so by default it follows
    // the window background. Reactive on its own (not a DisposableEffect key):
    // a theme toggle re-tints without tearing the backdrop down and up. The
    // Windows 10 accent tier carries its tint in the accent policy already
    // and must not be tinted twice.
    val resolvedBackground = LocalWindowClearColorLayers.current?.observableResolved?.value
    val appliedTier = holder?.appliedTier ?: WindowsBackdropAppliedTier.None
    val composeTint =
        when {
            appliedTier == WindowsBackdropAppliedTier.None ||
                appliedTier == WindowsBackdropAppliedTier.Accent -> 0
            tint.isSpecified -> tint.toArgb()
            style == WindowsBackdropStyle.Acrylic && resolvedBackground != null ->
                withAlpha(resolvedBackground, DEFAULT_ACRYLIC_TINT_ALPHA)
            else -> 0
        }
    SideEffect {
        holder?.let { WindowsBackdropMode.updateTint(taoWindow, it, tint, composeTint) }
    }
}

/** Which implementation ended up showing, as reported by the native side. */
internal enum class WindowsBackdropAppliedTier {
    None,
    Modern,
    LegacyMica,
    Accent,
    ;

    internal companion object {
        // Wire values of nativeSetBackdropStyle's return — the TIER_*
        // constants in nucleus_tao_windows_deco.c.
        fun fromNative(value: Int): WindowsBackdropAppliedTier =
            when (value) {
                WindowsBackdropTier.Modern.nativeValue -> Modern
                WindowsBackdropTier.LegacyMica.nativeValue -> LegacyMica
                WindowsBackdropTier.Windows10Acrylic.nativeValue -> Accent
                else -> None
            }
    }
}

/**
 * Alpha of the default Acrylic tint layer: enough to pull the material toward
 * the app's palette, translucent enough to keep the blur readable as such.
 */
private const val DEFAULT_ACRYLIC_TINT_ALPHA = 0x99

@Suppress("MagicNumber")
private fun withAlpha(
    argb: Int,
    alpha: Int,
): Int = (argb and 0x00FFFFFF) or (alpha shl 24)

/**
 * Tracks the backdrop holders of each window as an ordered stack: the most
 * recently acquired holder's style is the one showing (last-writer-wins), and
 * releasing it re-applies the next survivor's — releasing the last restores
 * `DWMSBT_AUTO` and the opaque background. A holder whose native apply fails
 * is never stacked, so its release cannot tear down a survivor's backdrop.
 *
 * Runs on the Tao main thread only, so no synchronization is needed.
 */
internal object WindowsBackdropMode {
    internal class Holder(
        val style: WindowsBackdropStyle,
        var tint: Color,
        val tier: WindowsBackdropTier,
        var composeTint: Int,
        var appliedTier: WindowsBackdropAppliedTier,
    )

    private class WindowEntry(
        val transparencyState: MutableState<Boolean>?,
        val composeTintState: MutableState<Int>?,
        val holders: MutableList<Holder> = mutableListOf(),
    )

    private val windows = HashMap<Long, WindowEntry>()

    private val supported: Boolean
        get() = Platform.Current == Platform.Windows && NativeTaoWindowsDecoBridge.isLoaded

    fun acquire(
        window: TaoWindow,
        style: WindowsBackdropStyle,
        tint: Color,
        tier: WindowsBackdropTier,
        transparencyState: MutableState<Boolean>?,
        composeTintState: MutableState<Int>?,
    ): Holder? {
        if (!supported) return null
        val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
        if (hwnd == 0L) return null

        val holder = Holder(style, tint, tier, composeTint = 0, WindowsBackdropAppliedTier.None)
        val applied = applyNative(hwnd, holder)
        // The backdrop apply rewrites the window's DWM/frame state and can
        // drop WS_EX_TOPMOST on the way (#631); reassert the requested
        // z-order after it.
        window.reassertAlwaysOnTop()
        // A style the OS declined is not stacked at all: transparency over a
        // backdrop that was never drawn renders as black, and an unstacked
        // holder's release can never underflow a survivor's slot.
        if (style.isActive && applied == WindowsBackdropAppliedTier.None) return null
        holder.appliedTier = applied

        val entry = windows.getOrPut(window.handle) { WindowEntry(transparencyState, composeTintState) }
        entry.holders.add(holder)
        entry.transparencyState?.value = applied != WindowsBackdropAppliedTier.None
        return holder
    }

    fun release(
        window: TaoWindow,
        holder: Holder,
    ) {
        if (!supported) return
        val entry = windows[window.handle] ?: return
        val wasTop = entry.holders.lastOrNull() === holder
        if (!entry.holders.remove(holder)) return

        if (entry.holders.isEmpty()) {
            // Drop the entry rather than keeping it empty: handles are native
            // pointers and can be recycled by a later window.
            windows.remove(window.handle)
            entry.transparencyState?.value = false
            entry.composeTintState?.value = 0
            val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
            if (hwnd != 0L) {
                NativeTaoWindowsDecoBridge.nativeSetBackdropStyle(
                    hwnd,
                    WindowsBackdropStyle.Default.nativeValue,
                    0,
                    false,
                    WindowsBackdropTier.Auto.nativeValue,
                )
            }
            // See acquire: backdrop teardown is a style rewrite too (#631).
            window.reassertAlwaysOnTop()
            return
        }
        if (wasTop) {
            // The survivor's backdrop comes back exactly as it had it — style,
            // native tint and Compose tint layer alike.
            val top = entry.holders.last()
            val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
            if (hwnd != 0L) top.appliedTier = applyNative(hwnd, top)
            entry.transparencyState?.value = top.appliedTier != WindowsBackdropAppliedTier.None
            entry.composeTintState?.value = top.composeTint
            window.reassertAlwaysOnTop()
        }
    }

    /**
     * Reactive tint path: re-tints in place, never tears the backdrop down.
     * The accent (Windows 10) tier carries the tint natively and needs a
     * native re-apply; the modern tiers only update the Compose clear layer.
     */
    fun updateTint(
        window: TaoWindow,
        holder: Holder,
        tint: Color,
        composeTint: Int,
    ) {
        if (!supported) return
        val entry = windows[window.handle] ?: return
        val isTop = entry.holders.lastOrNull() === holder
        val nativeTintChanged = holder.tint != tint
        holder.tint = tint
        holder.composeTint = composeTint
        if (!isTop) return
        entry.composeTintState?.value = composeTint
        if (nativeTintChanged && holder.appliedTier == WindowsBackdropAppliedTier.Accent) {
            val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
            if (hwnd != 0L) holder.appliedTier = applyNative(hwnd, holder)
        }
    }

    private fun applyNative(
        hwnd: Long,
        holder: Holder,
    ): WindowsBackdropAppliedTier =
        WindowsBackdropAppliedTier.fromNative(
            NativeTaoWindowsDecoBridge.nativeSetBackdropStyle(
                hwnd,
                holder.style.nativeValue,
                if (holder.tint.isSpecified) holder.tint.toArgb() else 0,
                holder.tint.isSpecified,
                holder.tier.nativeValue,
            ),
        )
}
