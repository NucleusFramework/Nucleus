package dev.nucleusframework.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.LocalRequestedTitleBarHeight
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.utils.linux.rememberLinuxButtonLayout

/**
 * Design-system-agnostic window chrome host: an alternative to composing
 * `TitleBar` directly that supports full-window content layouts
 * (see NucleusFramework/Nucleus#129).
 *
 * The [titleBar] slot accepts any composable — the built-in `TitleBar`, or a
 * fully custom chrome (a design system's toolbar/headerbar). The scaffold:
 * - measures the slot and publishes its height to the native layer (macOS
 *   traffic-light centering, Windows caption zone), replacing the fixed
 *   `TitleBarStyle.metrics.height` contract;
 * - provides [LocalWindowChromeInsets] so the chrome and the content can
 *   avoid the platform-reserved control zones (traffic lights, KDE edge
 *   padding);
 * - in [TitleBarPlacement.Overlay] mode, lets the content extend through the
 *   full window height behind the bar, handing it the bar height as a top
 *   padding.
 *
 * Custom chrome must declare its draggable surface explicitly with
 * [windowDragArea] — nothing is implicit at the scaffold level.
 *
 * Intended to be the sole child of the `DecoratedWindow` content lambda; it
 * fills the remaining window height.
 */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
public fun DecoratedWindowScope.WindowScaffold(
    modifier: Modifier = Modifier,
    titleBar: (@Composable () -> Unit)? = null,
    titleBarPlacement: TitleBarPlacement = TitleBarPlacement.Docked,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    content: @Composable (PaddingValues) -> Unit,
) {
    // Tao always provides a [TaoDecoratedWindowScope] at runtime — same
    // contract as `BasicTitleBar`.
    val taoScope = this as TaoDecoratedWindowScope
    val taoWindow = taoScope.window
    val currentState = taoScope.state

    val heightHolder = LocalRequestedTitleBarHeight.current
    val density = LocalDensity.current

    // Honour the marker modifier like `BasicTitleBar` does, so a scaffold-based
    // window can opt into the macOS 26 large corner radius without going
    // through the `MacOSStyle` window-creation parameter.
    if (Platform.Current == Platform.MacOS && modifier.hasMacOSLargeCornerRadius()) {
        LaunchedEffect(taoWindow) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L && NativeMetalBridge.isLoaded) {
                NativeMetalBridge.nativeApplyLargeCornerRadius(nsView, true)
            }
        }
    }

    // Effective bar height. Starts from whatever the window currently
    // requested (28 dp native default) so [LocalWindowChromeInsets] is
    // meaningful even with no title bar slot at all.
    var titleBarHeight by remember { mutableStateOf(heightHolder.value.dp) }

    val linuxLayout = if (Platform.Current == Platform.Linux) rememberLinuxButtonLayout() else null
    val controlDir = controlButtonsDirection.resolve()
    val controlIsRtl = controlDir == LayoutDirection.Rtl

    // macOS: flip the AppKit traffic-lights to the right edge when RTL is
    // active — mirrors `BasicTitleBar`.
    if (Platform.Current == Platform.MacOS) {
        LaunchedEffect(taoWindow, controlIsRtl) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(taoWindow.handle)
            if (nsView != 0L && NativeMetalBridge.isLoaded) {
                NativeMetalBridge.nativeSetButtonLayoutRtl(nsView, controlIsRtl)
            }
        }
    }

    val hideBar =
        titleBar == null ||
            (
                titleBarPlacement is TitleBarPlacement.Overlay &&
                    titleBarPlacement.autoHideInFullscreen &&
                    currentState.isFullscreen
            )

    // When the bar is not composed (null slot or overlay auto-hide in
    // fullscreen), reserve nothing: a non-zero controlsInsets based on the
    // last measured height would leave a phantom traffic-light / KDE pad over
    // full-window content (macOS fullscreen used to keep 80.dp).
    val chromeInsets =
        WindowChromeInsets(
            controlsInsets =
                if (hideBar) {
                    PaddingValues(0.dp)
                } else {
                    titleBarPadding(
                        measuredHeight = titleBarHeight,
                        isFullscreen = currentState.isFullscreen,
                        controlIsRtl = controlIsRtl,
                        linuxControlsOnRight = linuxLayout?.controlsOnRight,
                    )
                },
            titleBarHeight = if (hideBar) 0.dp else titleBarHeight,
        )

    val barSlot: @Composable () -> Unit = {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val measured = with(density) { size.height.toDp() }
                        if (measured != titleBarHeight) titleBarHeight = measured
                        // Drives macOS traffic-light centering and the Windows
                        // caption zone — same channel `TitleBar` uses, so
                        // nesting the legacy bar in the slot stays coherent.
                        heightHolder.value = measured.value
                    },
        ) {
            titleBar?.invoke()
        }
    }

    // Keep the shared height channel in sync with what the bar actually
    // occupies. `barSlot`'s onSizeChanged cannot do it alone: when the bar is
    // hidden the slot is not composed at all, so the last measured value would
    // linger — and the host re-pushes it to native on every resize / scale
    // change (`syncTitleBarHeight`), re-arming a caption zone over content and
    // centring the macOS traffic-lights against a bar that is not there.
    // `BasicTitleBar` does the same in its overlay branch.
    SideEffect {
        if (hideBar) heightHolder.value = 0f
    }

    // Windows: push the caption height to the deco WndProc, exactly like
    // `BasicTitleBar` does. Writing `heightHolder` alone is not enough — the
    // host only forwards it to the native side on resize / scale changes, so
    // a scaffold-based window would keep the 32 dp creation-time default
    // until the user resized it (breaking the touch caption drag and the
    // restore-from-maximized anchor). In `Overlay` mode the bar floats over
    // the content but still occupies the same top band, so the same measured
    // height applies; a hidden bar reserves nothing.
    if (Platform.Current == Platform.Windows) {
        val captionPx = with(density) { chromeInsets.titleBarHeight.roundToPx() }
        LaunchedEffect(taoWindow, captionPx) {
            if (!NativeTaoWindowsDecoBridge.isLoaded) return@LaunchedEffect
            val hwnd = NativeTaoBridge.nativeHwndHandle(taoWindow.handle)
            if (hwnd == 0L) return@LaunchedEffect
            NativeTaoWindowsDecoBridge.nativeSetTitleBarHeight(hwnd, captionPx)
        }
    }

    CompositionLocalProvider(LocalWindowChromeInsets provides chromeInsets) {
        with(taoScope) {
            when (titleBarPlacement) {
                is TitleBarPlacement.Docked ->
                    Column(modifier = modifier.weight(1f).fillMaxWidth()) {
                        if (!hideBar) barSlot()
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            content(PaddingValues(0.dp))
                        }
                    }

                is TitleBarPlacement.Overlay ->
                    Box(modifier = modifier.weight(1f).fillMaxWidth()) {
                        content(PaddingValues(top = chromeInsets.titleBarHeight))
                        if (!hideBar) {
                            // `passThroughToContent`: the bar floats over the
                            // content, so by default it wins the hit test for
                            // its whole band. Opting in shares the hit test with
                            // the content sibling below, keeping controls the app
                            // merged into the band interactive (see
                            // Modifier.shareHitTestWithSiblings).
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .let {
                                            if (titleBarPlacement.passThroughToContent) {
                                                it.shareHitTestWithSiblings()
                                            } else {
                                                it
                                            }
                                        },
                            ) {
                                barSlot()
                            }
                        }
                    }
            }
        }
    }
}
