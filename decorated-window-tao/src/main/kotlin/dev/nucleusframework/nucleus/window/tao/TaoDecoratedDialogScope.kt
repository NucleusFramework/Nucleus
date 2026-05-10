package dev.nucleusframework.nucleus.window.tao

import androidx.compose.foundation.layout.ColumnScope
import dev.nucleusframework.nucleus.window.DecoratedDialogScope

/**
 * Tao-specific sub-interface of [DecoratedDialogScope] adding access to the
 * Tao-owned [window] handle. Mirrors `AwtDecoratedDialogScope`'s relationship
 * with the core scope.
 */
interface TaoDecoratedDialogScope :
    DecoratedDialogScope,
    ColumnScope {
    val window: TaoWindow
}
