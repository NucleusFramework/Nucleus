package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.ColumnScope
import io.github.kdroidfilter.nucleus.window.DecoratedWindowScope as CoreDecoratedWindowScope

/**
 * Receiver of the `DecoratedWindow { … }` content lambda.
 *
 * Sub-interface of `decorated-window-core`'s [CoreDecoratedWindowScope] adding
 * the Tao-specific [window] handle (no AWT). Also extends [ColumnScope] so
 * `Modifier.weight(...)` is available for the body content directly under
 * `TitleBar()`, matching the layout convention used by the JBR/JNI backends.
 */
interface DecoratedWindowScope : CoreDecoratedWindowScope, ColumnScope {
    val window: TaoWindow
}
