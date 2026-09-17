package dev.nucleusframework.window

import androidx.compose.foundation.layout.PaddingValues

/**
 * Where [WindowScaffold] places its title bar relative to the window content.
 */
public sealed interface TitleBarPlacement {
    /**
     * The title bar is laid out above the content — the classic decorated
     * window layout, matching what composing `TitleBar` directly does today.
     */
    public data object Docked : TitleBarPlacement

    /**
     * The title bar is drawn as an overlay on top of the content, which fills
     * the full window height (macOS `fullSizeContentView`-style layouts). The
     * content receives the measured bar height as a top [PaddingValues] and
     * may scroll behind the bar.
     *
     * @param autoHideInFullscreen when `true`, the bar is not composed while
     * the window is fullscreen so the content is fully immersive.
     * @param passThroughToContent when `true`, presses landing on the bar are
     * ALSO hit-tested against the content below it, so controls the app merges
     * into the title-bar band (a collapsed navigation pane's back/hamburger
     * buttons, a tab strip) stay interactive through the overlay. Content that
     * consumes the press vetoes the window drag, exactly like an interactive
     * child of the bar itself. Off by default: with a full-bleed content layout
     * (a list or an image scrolling behind the bar) it would make a click on
     * the opaque chrome activate whatever sits underneath.
     */
    public data class Overlay(
        val autoHideInFullscreen: Boolean = true,
        val passThroughToContent: Boolean = false,
    ) : TitleBarPlacement
}
