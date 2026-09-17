package dev.nucleusframework.window.tao

/**
 * Linux window identity accepted by the XDG Desktop Portal `parent_window`
 * argument (and by FileKit's `FileKitDialogParent.x11` / `.wayland`).
 *
 * Construct via [TaoWindow.xdgPortalParent] — it picks X11 or Wayland from the
 * live GDK backend. On Wayland the [Wayland] variant owns an [XdgForeignExport]
 * that must stay open until portal dialogs finish.
 */
public sealed class XdgPortalParent {
    /** Full portal string: `x11:<hex>` or `wayland:<token>`. */
    public abstract val portalParent: String

    /**
     * X11 window id (XID). [portalParent] is `x11:` plus the bare lowercase
     * hexadecimal XID, matching the
     * [portal window identifier](https://flatpak.github.io/xdg-desktop-portal/docs/window-identifiers.html)
     * and FileKit's serialization.
     */
    public data class X11(
        public val xid: Long,
    ) : XdgPortalParent() {
        init {
            require(xid in 1L..X11_XID_MAX) {
                "An X11 XID must be between 1 and 0xffffffff."
            }
        }

        override val portalParent: String
            get() = "x11:${xid.toString(radix = 16)}"

        override fun toString(): String = "XdgPortalParent.X11"
    }

    /**
     * Wayland `xdg_foreign` export. Keep [export] open (or call [close]) only
     * after every portal dialog that borrowed [handle] has completed.
     */
    public class Wayland(
        public val export: XdgForeignExport,
    ) : XdgPortalParent(),
        AutoCloseable {
        public val handle: String
            get() = export.handle

        override val portalParent: String
            get() = export.portalParent

        override fun close() {
            export.close()
        }

        override fun toString(): String = "XdgPortalParent.Wayland(closed=${export.isClosed})"
    }

    private companion object {
        const val X11_XID_MAX: Long = 0xffff_ffffL
    }
}
