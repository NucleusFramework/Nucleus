@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.lab.probes.window.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.application.DefaultNucleusDialogHost
import dev.nucleusframework.application.DefaultNucleusWindowHost
import dev.nucleusframework.application.NucleusDecoratedDialogScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.NucleusDialogHost
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.NucleusWindowHost

/** `true` only inside content composed through [LabWindowHost] / [LabDialogHost]. */
val LocalViaLabHost = staticCompositionLocalOf { false }

/**
 * What an app installs to theme every secondary window a library opens: delegates to the
 * default host, records that it was asked, prefixes the title and marks the content as its own
 * through [LocalViaLabHost].
 */
class LabWindowHost(
    private val onInvoked: () -> Unit,
) : NucleusWindowHost {
    @Composable
    override fun Window(
        onCloseRequest: () -> Unit,
        state: WindowState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        minimizable: Boolean,
        maximizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        alwaysOnTop: Boolean,
        undecorated: Boolean,
        popupFor: NucleusWindow?,
        nativePopupLayers: Boolean,
        nativeContextMenu: Boolean,
        hiddenFromDock: Boolean,
        minimumSize: DpSize?,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        alwaysOnBottom: Boolean,
        content: @Composable @UiComposable NucleusDecoratedWindowScope.() -> Unit,
    ) {
        LaunchedEffect(Unit) { onInvoked() }
        DefaultNucleusWindowHost.Window(
            onCloseRequest,
            state,
            visible,
            "[Lab host] $title",
            icon,
            resizable,
            minimizable,
            maximizable,
            enabled,
            focusable,
            alwaysOnTop,
            undecorated,
            popupFor,
            nativePopupLayers,
            nativeContextMenu,
            hiddenFromDock,
            minimumSize,
            onPreviewKeyEvent,
            onKeyEvent,
            alwaysOnBottom,
        ) {
            CompositionLocalProvider(LocalViaLabHost provides true) { content() }
        }
    }
}

class LabDialogHost(
    private val onInvoked: () -> Unit,
) : NucleusDialogHost {
    @Composable
    override fun Dialog(
        onCloseRequest: () -> Unit,
        state: DialogState,
        visible: Boolean,
        title: String,
        icon: Painter?,
        resizable: Boolean,
        enabled: Boolean,
        focusable: Boolean,
        onPreviewKeyEvent: (KeyEvent) -> Boolean,
        onKeyEvent: (KeyEvent) -> Boolean,
        content: @Composable @UiComposable NucleusDecoratedDialogScope.() -> Unit,
    ) {
        LaunchedEffect(Unit) { onInvoked() }
        DefaultNucleusDialogHost.Dialog(
            onCloseRequest,
            state,
            visible,
            "[Lab host] $title",
            icon,
            resizable,
            enabled,
            focusable,
            onPreviewKeyEvent,
            onKeyEvent,
        ) {
            CompositionLocalProvider(LocalViaLabHost provides true) { content() }
        }
    }
}
