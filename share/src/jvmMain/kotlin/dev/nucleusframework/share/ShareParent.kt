package dev.nucleusframework.share

/**
 * The desktop window a share UI is attached to.
 *
 * With Tao windows: `ShareParent.Windows(window.nativeHandle)`,
 * `ShareParent.MacOs(window.nsWindowHandle!!)`,
 * `ShareParent.Linux(parent.portalParent, keepAlive = parent)` with `parent = window.xdgPortalParent()!!`
 * — or, from `nucleus-application`, `NucleusWindow.share`, which resolves all of this.
 */
public sealed interface ShareParent {
    /** The app's frontmost window (on Linux, the active X11 window, if any). */
    public data object Auto : ShareParent

    /** A Windows `HWND`; a child window resolves to its top-level window. */
    public data class Windows(
        public val hwnd: Long,
    ) : ShareParent

    /**
     * A macOS `NSWindow*`.
     *
     * @property anchor where the picker points to, e.g. the bounds of the button that
     * triggered it; the top centre of the window when `null`.
     */
    public data class MacOs(
        public val nsWindow: Long,
        public val anchor: ShareAnchor? = null,
    ) : ShareParent

    /**
     * An XDG portal
     * [parent window identifier](https://flatpak.github.io/xdg-desktop-portal/docs/window-identifiers.html):
     * `x11:<hex xid>` or `wayland:<xdg-foreign handle>`.
     *
     * @property keepAlive closed once the portal dialog is gone — or right after
     * [ShareSheet.share] when no dialog was shown — never earlier. A Wayland handle
     * must stay exported until then: pass its `xdg_foreign` export here.
     */
    public data class Linux(
        public val portalParent: String,
        public val keepAlive: AutoCloseable? = null,
    ) : ShareParent
}

/** A rectangle in points (logical pixels), relative to the top-left corner of the window's content. */
public data class ShareAnchor(
    public val x: Double,
    public val y: Double,
    public val width: Double,
    public val height: Double,
)
