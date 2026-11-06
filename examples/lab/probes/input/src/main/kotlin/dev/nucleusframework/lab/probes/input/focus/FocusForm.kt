package dev.nucleusframework.lab.probes.input.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.CheckboxRow
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabeledRow
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.TertiaryAction
import dev.nucleusframework.lab.designsystem.TextArea
import dev.nucleusframework.lab.designsystem.TextField

/** Reports gained/lost focus of the target called [name], ignoring repeated identical states. */
@Composable
private fun Modifier.tracked(
    name: String,
    vm: FocusViewModel,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged {
        // hasFocus, not isFocused: the modifier may sit on a component's wrapper, around the focusable node.
        if (it.hasFocus == focused) return@onFocusChanged
        focused = it.hasFocus
        if (focused) vm.onFocusGained(name) else vm.onFocusLost(name)
    }
}

@Composable
internal fun FocusForm(vm: FocusViewModel) {
    val (name, email, subscribe, notes, cancel, submit) = FocusTargets
    val emailFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var nameText by rememberSaveable { mutableStateOf("") }
    var emailText by rememberSaveable { mutableStateOf("") }
    var notesText by rememberSaveable { mutableStateOf("") }
    var subscribed by rememberSaveable { mutableStateOf(false) }

    // The tracked targets are ordinary Lab (Jewel) fields and buttons: the subject is focus
    // traversal through a real form, not any one design system.
    Column(verticalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
        LabeledRow(name) {
            TextField(nameText, { nameText = it }, Modifier.weight(1f).tracked(name, vm))
        }
        LabeledRow(email) {
            TextField(emailText, { emailText = it }, Modifier.weight(1f).focusRequester(emailFocus).tracked(email, vm))
        }
        CheckboxRow(subscribe, subscribed, Modifier.tracked(subscribe, vm)) { subscribed = it }
        LabeledRow(notes) {
            TextArea(notesText, { notesText = it }, Modifier.weight(1f).tracked(notes, vm), minLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
            SecondaryAction(cancel, modifier = Modifier.tracked(cancel, vm)) {}
            PrimaryAction(submit, modifier = Modifier.tracked(submit, vm)) {}
        }
    }
    Actions {
        TertiaryAction("Focus Email") { emailFocus.requestFocus() }
        TertiaryAction("Next") { focusManager.moveFocus(FocusDirection.Next) }
        TertiaryAction("Clear focus") { focusManager.clearFocus() }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
