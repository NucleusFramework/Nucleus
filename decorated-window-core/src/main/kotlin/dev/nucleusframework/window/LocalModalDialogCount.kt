package dev.nucleusframework.window

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * Counts the modal dialogs opened at *application* scope — outside any
 * decorated window's content, e.g. a settings dialog hoisted next to the
 * window list so a single instance is shared by every window.
 *
 * It is the default value of [LocalModalDialogCount]: a dialog composed at
 * application scope has no window-provided counter at its call site, so its
 * increment lands here. Every decorated window observes this counter *in
 * addition to* its own, which makes application-scope dialogs
 * application-modal: all open windows (including ones created while the
 * dialog is up) render their input blocker.
 */
public val GlobalModalDialogCount: MutableState<Int> = mutableStateOf(0)

/**
 * Counts the number of modal dialogs currently open above a decorated window.
 *
 * Provided (with a fresh [MutableState]`<Int>`) by each decorated window in
 * its scene setup, so overlapping windows each have their own counter.
 * Defaults to [GlobalModalDialogCount] so a dialog composed outside any
 * window becomes application-modal instead of incrementing a counter nobody
 * observes.
 *
 * When a [DecoratedDialog] opens, it reads this local from the bridged outer
 * context and increments it; the parent window reacts by rendering a
 * full-screen transparent input blocker that consumes all pointer events so
 * the content underneath is not interactive while the dialog is visible.
 * The counter decrements on dialog dispose, restoring interactivity.
 */
public val LocalModalDialogCount: ProvidableCompositionLocal<MutableState<Int>> =
    compositionLocalOf<MutableState<Int>> {
        GlobalModalDialogCount
    }
