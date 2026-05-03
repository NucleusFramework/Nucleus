/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.awt

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.viewinterop.InteropViewGroup
import java.awt.event.FocusEvent.Cause.TRAVERSAL_BACKWARD
import java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD

/**
 * This class is for supporting seamless focus switching between Compose and SwingPanel components:
 * - adds a special [modifier] that redirects focus from Compose to the SwingPanel components
 * - adds [moveBeforeInteropView], [moveAfterInteropView] that
 *   redirect focus from SwingPanel to Compose
 *
 * See ComposeFocusTest for all edge cases.
 */
internal class InteropFocusSwitcher(
    private val group: InteropViewGroup,
    private val focusManager: FocusManager,
) {
    private val focusPolicy get() = group.focusTraversalPolicy

    private var tracker = FocusTracker(
        properties = {
            canFocus = focusPolicy.getDefaultComponent(group) != null
        },
        onFocus = {
            when (it) {
                FocusDirection.Next, FocusDirection.Enter -> {
                    focusPolicy.getFirstComponent(group)?.requestFocus(TRAVERSAL_FORWARD)
                }
                FocusDirection.Previous -> {
                    focusPolicy.getLastComponent(group)?.requestFocus(TRAVERSAL_BACKWARD)
                }
            }
        }
    )

    val modifier get() = tracker.modifier

    fun moveBeforeInteropView() {
        tracker.requestFocusWithoutEvent()
        focusManager.moveFocus(FocusDirection.Previous)
    }

    fun moveAfterInteropView() {
        tracker.requestFocusWithoutEvent()
        focusManager.moveFocus(FocusDirection.Next)
    }
}

private class FocusTracker(
    properties: FocusProperties.() -> Unit,
    private val onFocus: (FocusDirection) -> Unit,
) {
    private val requester = FocusRequester()
    private var isRequestingFocus = false
    private var lastEnteredDirection = FocusDirection.Enter

    fun requestFocusWithoutEvent() {
        try {
            isRequestingFocus = true
            requester.requestFocus()
        } finally {
            isRequestingFocus = false
        }
    }

    private val childModifier = Modifier
        .focusProperties(properties)
        .focusRequester(requester)
        .onFocusEvent {
            if (!isRequestingFocus && it.isFocused) {
                // `parentModifier.onEnter` is always called before `childModifier.onFocusEvent`,
                // so we always have the actual value
                onFocus(lastEnteredDirection)
            }
        }
        .focusTarget()

    private val parentModifier = Modifier
        .focusProperties {
            canFocus = false
            onEnter = {
                lastEnteredDirection = requestedFocusDirection
            }
        }
        .focusTarget()

    // 2 modifiers:
    // - parent to intercept onEnter, but without focusing on it
    // - child to handle the focus
    //
    // The logic parent/child is got from [Modifier.focusInteropModifier].
    //
    // The difference with Android is that the [childModifier] is requesting the SwingPanel focus,
    // not the [parentModifier].
    //
    // The reason for that is the case when SwingPanel is at the beginning of the window.
    // In this case we call `onRequestFocusForOwner` when we focus on the first node:
    //
    //     parentModifier.onEnter -> onRequestFocusForOwner -> childModifier.onFocusEvent
    //
    // If we would also place focusing on a SwingPanel component between
    // onEnter an onRequestFocusForOwner, onRequestFocusForOwner will override the focus.
    //
    // The issue may exist on Android too.
    val modifier = Modifier
        .then(parentModifier)
        .then(childModifier)
}