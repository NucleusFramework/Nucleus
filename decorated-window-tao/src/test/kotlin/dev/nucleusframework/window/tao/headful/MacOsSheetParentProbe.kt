package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Thin wrapper around the macOS headful helper that parents a real
 * [NSOpenPanel] sheet on a Tao [NSWindow] (mirrors [XdgPortalFileChooser] for
 * the Linux portal path).
 */
internal object MacOsSheetParentProbe {
    const val OK: Int = 1
    const val WINDOW_NOT_FOUND: Int = 0
    const val VIEW_NOT_IN_HIERARCHY: Int = -1
    const val SHEET_DID_NOT_ATTACH: Int = -2
    const val SHEET_DID_NOT_DISMISS: Int = -3

    fun probe(
        nsWindow: Long,
        nsView: Long,
    ): Int = NativeTaoBridge.nativeMacOsProbeSheetParent(nsWindow, nsView)

    fun describe(code: Int): String =
        when (code) {
            OK -> "ok (sheet attached and cancelled)"
            WINDOW_NOT_FOUND -> "NSWindow* not found in NSApp.windows"
            VIEW_NOT_IN_HIERARCHY -> "NSView* not in parent window view hierarchy"
            SHEET_DID_NOT_ATTACH -> "beginSheetModalForWindow did not attach a sheet"
            SHEET_DID_NOT_DISMISS -> "sheet failed to dismiss / completion did not fire"
            else -> "unknown code $code"
        }
}
