package dev.nucleusframework.window

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.painter.Painter

/**
 * Backend-agnostic scope exposed inside a decorated dialog. Each backend
 * (AWT-bound: jbr/jni, native: tao) provides its own sub-interface adding a
 * backend-specific window handle.
 */
@Stable
public interface DecoratedDialogScope {
    public val state: DecoratedDialogState
}

/**
 * Snapshot of a decorated dialog's runtime state. Only `isActive` is tracked;
 * dialogs cannot be minimized/maximized/fullscreen by design.
 */
@Immutable
@JvmInline
public value class DecoratedDialogState(
    public val state: ULong,
) {
    public val isActive: Boolean
        get() = state and Active != 0UL

    public fun copy(active: Boolean = isActive): DecoratedDialogState = of(active = active)

    public fun toDecoratedWindowState(): DecoratedWindowState =
        DecoratedWindowState.of(
            fullscreen = false,
            minimized = false,
            maximized = false,
            active = isActive,
        )

    override fun toString(): String = "${javaClass.simpleName}(isActive=$isActive)"

    public companion object {
        public val Active: ULong = 1UL shl 0

        public fun of(active: Boolean = true): DecoratedDialogState =
            DecoratedDialogState(
                if (active) Active else 0UL,
            )
    }
}

public data class DialogTitleBarInfo(
    val title: String,
    val icon: Painter?,
)

public val LocalDialogTitleBarInfo: ProvidableCompositionLocal<DialogTitleBarInfo> =
    compositionLocalOf {
        error("LocalDialogTitleBarInfo not provided, DialogTitleBar must be used in DecoratedDialog")
    }
