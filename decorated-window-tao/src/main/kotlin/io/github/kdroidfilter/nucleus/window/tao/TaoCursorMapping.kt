package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/**
 * Maps a Compose [PointerIcon] to the wire-format integer code consumed
 * by `NativeTaoBridge.nativeSetCursorIcon`.
 *
 * The four well-known singletons (`Default`, `Text`, `Hand`, `Crosshair`)
 * are matched by reference. Anything else is unwrapped via reflection
 * because Compose's `PointerIcon` doesn't expose its underlying AWT
 * cursor as a public API — every Tao host previously inlined this same
 * trick.
 */
internal fun PointerIcon.toTaoCursorIconCode(): Int {
    when (this) {
        PointerIcon.Default -> return TaoCursorIcon.DEFAULT
        PointerIcon.Text -> return TaoCursorIcon.TEXT
        PointerIcon.Hand -> return TaoCursorIcon.HAND
        PointerIcon.Crosshair -> return TaoCursorIcon.CROSSHAIR
    }
    return runCatching {
        val cursor = javaClass.getMethod("getCursor").invoke(this) as? Cursor
        when (cursor?.type) {
            Cursor.TEXT_CURSOR -> TaoCursorIcon.TEXT
            Cursor.HAND_CURSOR -> TaoCursorIcon.HAND
            Cursor.CROSSHAIR_CURSOR -> TaoCursorIcon.CROSSHAIR
            Cursor.WAIT_CURSOR -> TaoCursorIcon.WAIT
            Cursor.MOVE_CURSOR -> TaoCursorIcon.MOVE
            Cursor.E_RESIZE_CURSOR, Cursor.W_RESIZE_CURSOR -> TaoCursorIcon.EW_RESIZE
            Cursor.N_RESIZE_CURSOR, Cursor.S_RESIZE_CURSOR -> TaoCursorIcon.NS_RESIZE
            Cursor.NE_RESIZE_CURSOR, Cursor.SW_RESIZE_CURSOR -> TaoCursorIcon.NESW_RESIZE
            Cursor.NW_RESIZE_CURSOR, Cursor.SE_RESIZE_CURSOR -> TaoCursorIcon.NWSE_RESIZE
            else -> TaoCursorIcon.DEFAULT
        }
    }.getOrDefault(TaoCursorIcon.DEFAULT)
}
