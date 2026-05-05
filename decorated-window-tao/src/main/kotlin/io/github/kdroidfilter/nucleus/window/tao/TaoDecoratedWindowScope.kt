package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.ColumnScope
import io.github.kdroidfilter.nucleus.window.DecoratedWindowScope

/**
 * Tao-specific sub-interface of [DecoratedWindowScope] adding access to the
 * Tao-owned [window] handle (no AWT). Mirrors `AwtDecoratedWindowScope`'s
 * relationship with the core scope.
 *
 * Also extends [ColumnScope] so `Modifier.weight(...)` is available for the
 * body content directly under `TitleBar()`, matching the layout convention
 * used by the JBR/JNI backends.
 */
interface TaoDecoratedWindowScope :
    DecoratedWindowScope,
    ColumnScope {
    val window: TaoWindow
}
