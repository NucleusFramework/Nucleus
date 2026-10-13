package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoCursorIcon
import dev.nucleusframework.window.tao.TaoPointerIcon
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.awt.Cursor
import java.util.concurrent.ConcurrentHashMap

/**
 * #746 — every cursor a Compose host can request must show its own macOS
 * shape, through the real hover path (AWT Robot → Tao → scene host →
 * `nativeSetCursorIcon` / `nativeSetPanelCursor`), without taking the process
 * down. `+[NSCursor _moveCursor]` throws on macOS 27, which aborted the JVM on
 * a MOVE hover; the diagonal resizes went through private factories as well.
 *
 * The oracle is `[NSCursor currentCursor]` read back after the hover has
 * settled — i.e. after Tao's cursor rect has re-asserted itself — compared
 * by look (hotspot, size, pixels) with Nucleus' table, and every shape but the
 * arrow must differ from the arrow: a fallback is not a pass.
 */
internal object MacOsCursorShapeHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(everyCursorInWindow(), awtCursorsInWindow(), cursorsInNativePopup())

    /** Every `TaoCursorIcon` code, through Nucleus' own pointer icons. */
    private fun everyCursorInWindow(): TaoWindowTestCase {
        val cells = (0 until CODE_COUNT).map { code -> Cell(code, TaoPointerIcon(code)) }
        return cursorCase("#746 every cursor shape in the window", cells, inPopup = false)
    }

    /** The issue's own repro: Compose Desktop's AWT-backed icons (MOVE, SW / SE resize). */
    private fun awtCursorsInWindow(): TaoWindowTestCase {
        val cells =
            listOf(
                Cell(TaoCursorIcon.MOVE, PointerIcon(Cursor(Cursor.MOVE_CURSOR))),
                Cell(TaoCursorIcon.NESW_RESIZE, PointerIcon(Cursor(Cursor.SW_RESIZE_CURSOR))),
                Cell(TaoCursorIcon.NWSE_RESIZE, PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR))),
            )
        return cursorCase("#746 AWT MOVE / SW / SE cursors in the window", cells, inPopup = false)
    }

    /** The native popup panel resolves codes through the same table. */
    private fun cursorsInNativePopup(): TaoWindowTestCase {
        val cells =
            listOf(TaoCursorIcon.MOVE, TaoCursorIcon.NESW_RESIZE, TaoCursorIcon.NWSE_RESIZE, TaoCursorIcon.HELP)
                .map { code -> Cell(code, TaoPointerIcon(code)) }
        return cursorCase("#746 cursor shapes in a native popup panel", cells, inPopup = true)
    }

    private fun cursorCase(
        name: String,
        cells: List<Cell>,
        inPopup: Boolean,
    ): TaoWindowTestCase {
        val scene = SceneSize()
        val centers = ConcurrentHashMap<Int, Offset>()
        return TaoWindowTestCase(
            name = name,
            skip = ::macOnly,
            nativePopupLayers = inPopup,
            paintDefaultBackground = false,
            content = {
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned { scene.value = it.size },
                    contentAlignment = Alignment.Center,
                ) {
                    if (inPopup) {
                        Popup(alignment = Alignment.Center) { Grid(cells, centers) }
                    } else {
                        Grid(cells, centers)
                    }
                }
            },
        ) {
            awaitUntil("scene measured") { scene.value.width > 0 && centers.size == cells.size }
            settle()

            // Window coordinates in both cases: a native popup's root reports
            // its bounds in the owner's scene.
            fun center(index: Int): Offset = checkNotNull(centers[index])
            val driver = RobotPointerDriver(window) { scene.value }
            window.focus()

            val arrow = signature(TaoCursorIcon.DEFAULT)
            cells.forEachIndexed { index, cell ->
                val expected = signature(cell.code)
                check(cell.code == TaoCursorIcon.DEFAULT || expected != arrow) {
                    "cursor ${cell.code} resolves to the arrow ($expected): a fallback, not its shape"
                }
                // Leave the cell's neighbour first so every hover is a real change.
                driver.moveTo(center((index + 1) % cells.size))
                settle(HOVER_SETTLE_MILLIS)
                driver.moveTo(center(index))
                awaitUntil(
                    "cursor ${cell.code} shown over its cell",
                    detail = {
                        "current=${signature(CURRENT)} expected=$expected " +
                            "lastRequested=${NativeTaoBridge.lastCursorIcon[window.handle]}"
                    },
                ) {
                    signature(CURRENT) == expected
                }
                // Tao rebuilds its cursor rect after the request; the shape it
                // re-asserts must be the same one.
                settle(HOVER_SETTLE_MILLIS)
                val current = signature(CURRENT)
                check(current == expected) {
                    "cursor ${cell.code} changed once the cursor rect re-asserted: current=$current expected=$expected"
                }
            }
            check(bounds() != null) { "the window must survive every hover" }
        }
    }

    @Composable
    private fun Grid(
        cells: List<Cell>,
        centers: ConcurrentHashMap<Int, Offset>,
    ) {
        Column {
            cells.chunked(COLUMNS).forEachIndexed { row, rowCells ->
                Row {
                    rowCells.forEachIndexed { column, cell ->
                        val index = row * COLUMNS + column
                        Box(
                            Modifier
                                .size(CELL_DP.dp)
                                .pointerHoverIcon(cell.icon)
                                .onGloballyPositioned { centers[index] = it.boundsInRoot().center },
                        )
                    }
                }
            }
        }
    }

    private fun signature(code: Int): String =
        checkNotNull(NativeTaoBridge.nativeDiagCursorSignature(code)) { "no cursor signature for $code" }

    private fun macOnly(): String? =
        if (Platform.Current != Platform.MacOS) "reads AppKit's current cursor" else robotDriverSkipReason()

    private class Cell(
        val code: Int,
        val icon: PointerIcon,
    )

    private class SceneSize {
        @Volatile
        var value: IntSize = IntSize.Zero
    }

    private const val CODE_COUNT = 15
    private const val CURRENT = -1
    private const val COLUMNS = 5
    private const val CELL_DP = 56
    private const val HOVER_SETTLE_MILLIS = 150L
}
