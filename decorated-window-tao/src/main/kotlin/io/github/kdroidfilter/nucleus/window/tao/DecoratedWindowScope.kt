package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.ColumnScope

/**
 * Receiver of the `DecoratedWindow { … }` content lambda.
 *
 * Mirrors `decorated-window-core`'s `DecoratedWindowScope`, except the [window]
 * here is a [TaoWindow] (no AWT). The scope also extends [ColumnScope] so
 * `Modifier.weight(...)` is available for the body content directly under
 * `TitleBar()`, matching the layout convention used by the JBR/JNI backends.
 */
interface DecoratedWindowScope : ColumnScope {
    val window: TaoWindow
    val state: DecoratedWindowState
}
