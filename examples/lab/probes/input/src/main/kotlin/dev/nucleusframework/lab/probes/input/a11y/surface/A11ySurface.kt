package dev.nucleusframework.lab.probes.input.a11y.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.nucleusframework.lab.designsystem.LabTheme

/**
 * Comprehensive accessibility test surface. Every interactive control here
 * is wired with explicit Compose semantics so the macOS NSAccessibility
 * projection can be exercised end-to-end via System Events / VoiceOver.
 *
 * Each section visually renders a counter or a state indicator that makes
 * the AX action's effect observable from outside (osascript test scripts
 * read these descriptions to assert success).
 *
 * **CI fixture**: `scripts/ci/a11y-goldens/a11y-tab.*.json` and the
 * `verify-atspi*.py` / `verify-uia*.ps1` / `verify-ax.swift` probes assert this
 * tree node by node (names, roles, states, actions). Any change to a label,
 * role or semantics block here must be mirrored in the goldens.
 *
 * [onEvent] reports every state change (whoever caused it: pointer, keyboard
 * or an assistive technology action) so the Lab can show *which thread* the
 * action landed on; it never changes the tree.
 */
@Composable
fun A11ySurface(
    modifier: Modifier = Modifier,
    onEvent: (String) -> Unit = {},
) {
    var clicks by remember { mutableIntStateOf(0) }
    var checkboxState by remember { mutableStateOf(ToggleableState.Off) }
    var switchOn by remember { mutableStateOf(false) }
    var radioSelected by remember { mutableIntStateOf(0) }
    var sliderValue by remember { mutableFloatStateOf(0.5f) }
    var textValue by remember { mutableStateOf(TextFieldValue("hello")) }
    var status by remember { mutableStateOf("Ready") }
    var dialogOpen by remember { mutableStateOf(false) }
    val sliderRange = 0f..1f

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(LabTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // ── Heading ────────────────────────────────────────────────────────
        BasicText(
            text = "Accessibility Test Surface",
            modifier = Modifier.semantics { heading() },
            style =
                LabTheme.typography.body.copy(
                    color = LabTheme.colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )

        // ── Button + counter (verifies AXPress flows through to Compose) ──
        Section("Button") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                A11yButton(
                    label = "Increment",
                    onClick = {
                        clicks++
                        onEvent("Increment -> clicks=$clicks")
                    },
                )
                BasicText(
                    text = "clicks: $clicks",
                    modifier = Modifier.semantics { contentDescription = "click counter $clicks" },
                    style = labelStyle,
                )
            }
        }

        // ── Disabled button (verifies isAccessibilityEnabled = false) ────
        Section("Disabled button") {
            A11yButton(
                label = "Cannot press",
                onClick = { clicks++ },
                enabled = false,
                tag = "disabled-btn",
            )
        }

        // ── Checkbox (tri-state) ──────────────────────────────────────────
        Section("Checkbox") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when (checkboxState) {
                                    ToggleableState.On -> LabTheme.colors.ok
                                    ToggleableState.Indeterminate -> LabTheme.colors.warning
                                    ToggleableState.Off -> LabTheme.colors.borderStrong
                                },
                            ).clickable {
                                checkboxState =
                                    when (checkboxState) {
                                        ToggleableState.Off -> ToggleableState.On
                                        ToggleableState.On -> ToggleableState.Indeterminate
                                        ToggleableState.Indeterminate -> ToggleableState.Off
                                    }
                                onEvent("Tri-state checkbox -> $checkboxState")
                            }.semantics {
                                role = Role.Checkbox
                                toggleableState = checkboxState
                                contentDescription = "Tri-state checkbox"
                            },
                )
                BasicText(text = "state: $checkboxState", style = labelStyle)
            }
        }

        // ── Switch ────────────────────────────────────────────────────────
        Section("Switch") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier =
                        Modifier
                            .width(40.dp)
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (switchOn) LabTheme.colors.ok else LabTheme.colors.borderStrong)
                            .clickable {
                                switchOn = !switchOn
                                onEvent("Notifications switch -> ${if (switchOn) "on" else "off"}")
                            }.semantics {
                                role = Role.Switch
                                toggleableState = if (switchOn) ToggleableState.On else ToggleableState.Off
                                contentDescription = "Notifications switch"
                            },
                )
                BasicText(text = if (switchOn) "ON" else "OFF", style = labelStyle)
            }
        }

        // ── Radio group ──────────────────────────────────────────────────
        Section("Radio buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Low", "Medium", "High").forEachIndexed { idx, label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .border(
                                        1.dp,
                                        if (radioSelected ==
                                            idx
                                        ) {
                                            LabTheme.colors.accent
                                        } else {
                                            LabTheme.colors.textDisabled
                                        },
                                        CircleShape,
                                    ).background(
                                        if (radioSelected == idx) LabTheme.colors.accent else Color.Transparent,
                                    ).clickable {
                                        radioSelected = idx
                                        onEvent("Priority -> $label")
                                    }.semantics {
                                        role = Role.RadioButton
                                        toggleableState =
                                            if (radioSelected == idx) ToggleableState.On else ToggleableState.Off
                                        contentDescription = "Priority $label"
                                    },
                        )
                        BasicText(text = label, style = labelStyle)
                    }
                }
            }
        }

        // ── Slider (AXIncrement / AXDecrement) ────────────────────────────
        Section("Slider") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier =
                        Modifier
                            .width(160.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(LabTheme.colors.raised)
                            .semantics {
                                contentDescription = "Volume"
                                progressBarRangeInfo =
                                    androidx.compose.ui.semantics.ProgressBarRangeInfo(
                                        current = sliderValue,
                                        range = sliderRange,
                                    )
                                setProgress { newValue ->
                                    sliderValue = newValue.coerceIn(sliderRange.start, sliderRange.endInclusive)
                                    onEvent("Volume setProgress -> ${"%.2f".format(sliderValue)}")
                                    true
                                }
                            },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(sliderValue)
                                .height(20.dp)
                                .background(LabTheme.colors.accent, RoundedCornerShape(10.dp)),
                    )
                }
                BasicText(
                    text = "value=${"%.2f".format(sliderValue)}",
                    style = labelStyle,
                )
            }
        }

        // ── TextField (NavigableStaticText + SetText) ─────────────────────
        Section("Text field") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier =
                        Modifier
                            .width(220.dp)
                            .background(LabTheme.colors.raised, RoundedCornerShape(6.dp))
                            .border(1.dp, LabTheme.colors.borderStrong, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    BasicTextField(
                        value = textValue,
                        onValueChange = {
                            if (it.text != textValue.text) onEvent("A11y text field -> '${it.text}'")
                            textValue = it
                        },
                        singleLine = true,
                        textStyle = labelStyle,
                        cursorBrush =
                            androidx.compose.ui.graphics
                                .SolidColor(LabTheme.colors.accent),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("a11y-text-field")
                                .semantics {
                                    // Named for UIA discovery + keyboard a11y probes.
                                    contentDescription = "A11y text field"
                                },
                    )
                }
                BasicText(
                    text = "len=${textValue.text.length} sel=${textValue.selection.start}..${textValue.selection.end}",
                    modifier =
                        Modifier.semantics {
                            contentDescription = "TextField status: text='${textValue.text}'"
                        },
                    style = labelStyle,
                )
            }
        }

        // ── Custom actions (VO+Cmd+. menu) ────────────────────────────────
        Section("Custom actions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(LabTheme.colors.raised)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Notification (clicks: $clicks)"
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction("Mark as read") {
                                            clicks += 100
                                            onEvent("custom action 'Mark as read' -> clicks=$clicks")
                                            true
                                        },
                                        CustomAccessibilityAction("Archive") {
                                            clicks += 1000
                                            onEvent("custom action 'Archive' -> clicks=$clicks")
                                            true
                                        },
                                    )
                            },
                ) {
                    BasicText(
                        text = "Notification — VO+Cmd+.",
                        style = labelStyle,
                    )
                }
            }
        }

        // ── Live region (announces on text change) ────────────────────────
        Section("Live region (assertive)") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                A11yButton(
                    label = "Update status",
                    onClick = {
                        val ts =
                            java.time.LocalTime
                                .now()
                                .withNano(0)
                        status = "Status updated at $ts"
                        onEvent("live region -> $status")
                    },
                )
                BasicText(
                    text = status,
                    modifier =
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Assertive
                            contentDescription = status
                        },
                    style = labelStyle,
                )
            }
        }

        // ── Toggleable without explicit Role (must surface as a checkable role,
        //    not Group, so AT-SPI exposes STATE_CHECKABLE / UIA Toggle pattern) ──
        Section("Toggleable (no role)") {
            var t by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (t) LabTheme.colors.ok else LabTheme.colors.borderStrong)
                            .clickable {
                                t = !t
                                onEvent("Bare toggleable -> ${if (t) "on" else "off"}")
                            }.testTag("bare-toggle")
                            .semantics {
                                // Intentionally no `role = ...` — pure toggleable.
                                toggleableState = if (t) ToggleableState.On else ToggleableState.Off
                                contentDescription = "Bare toggleable"
                            },
                )
                BasicText(text = if (t) "ON" else "OFF", style = labelStyle)
            }
        }

        // ── Form-field validation (SemanticsProperties.Error → AT-SPI
        //    STATE_INVALID_ENTRY) ───────────────────────────────────────────
        Section("Validation error") {
            var emailValue by remember { mutableStateOf(TextFieldValue("not-an-email")) }
            val invalid = !emailValue.text.contains('@')
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier =
                        Modifier
                            .width(220.dp)
                            .background(LabTheme.colors.raised, RoundedCornerShape(6.dp))
                            .border(
                                1.dp,
                                if (invalid) LabTheme.colors.error else LabTheme.colors.borderStrong,
                                RoundedCornerShape(6.dp),
                            ).padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    BasicTextField(
                        value = emailValue,
                        onValueChange = { emailValue = it },
                        singleLine = true,
                        textStyle = labelStyle,
                        cursorBrush =
                            androidx.compose.ui.graphics
                                .SolidColor(LabTheme.colors.accent),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("email-input")
                                .semantics {
                                    if (invalid) {
                                        error("Invalid email address")
                                    }
                                },
                    )
                }
                BasicText(
                    text = if (invalid) "invalid" else "ok",
                    style = labelStyle,
                )
            }
        }

        // ── Modal dialog ──────────────────────────────────────────────────
        Section("Modal dialog") {
            A11yButton(
                label = "Open dialog",
                onClick = {
                    dialogOpen = true
                    onEvent("dialog opened")
                },
            )
        }

        Spacer(Modifier.height(20.dp))
    }

    if (dialogOpen) {
        Dialog(onDismissRequest = { dialogOpen = false }) {
            Column(
                modifier =
                    Modifier
                        .background(
                            LabTheme.colors.raised,
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(8.dp),
                        ).padding(24.dp)
                        .semantics {
                            // IsDialog is auto-set by Compose's Dialog, but we
                            // also expose a Dismiss action so VoiceOver can close
                            // via VO+Esc.
                            this[SemanticsProperties.IsDialog] = Unit
                            dismiss {
                                dialogOpen = false
                                onEvent("dialog dismissed (a11y dismiss action)")
                                true
                            }
                        },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BasicText(
                    text = "Modal dialog",
                    modifier = Modifier.semantics { heading() },
                    style =
                        LabTheme.typography.body.copy(
                            color = LabTheme.colors.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
                BasicText(
                    text = "VoiceOver users press VO+Esc to dismiss.",
                    style = labelStyle,
                )
                A11yButton(
                    label = "Close",
                    onClick = {
                        dialogOpen = false
                        onEvent("dialog closed")
                    },
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = title,
            modifier = Modifier.semantics { heading() },
            style =
                LabTheme.typography.body.copy(
                    color = LabTheme.colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
        content()
    }
}

@Composable
private fun A11yButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val baseModifier =
        (if (tag != null) Modifier.testTag(tag) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) LabTheme.colors.raised else LabTheme.colors.panel)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    val finalModifier =
        if (enabled) {
            baseModifier
                .clickable { onClick() }
                .semantics { role = Role.Button }
        } else {
            // mergeDescendants like the enabled clickable path: without it the
            // label text stays a separate (enabled) static-text node and the
            // disabled button projects unlabeled — screen readers would read
            // "Cannot press" as plain active text.
            baseModifier.semantics(mergeDescendants = true) {
                role = Role.Button
                disabled()
            }
        }
    Box(modifier = finalModifier) {
        BasicText(
            text = label,
            style =
                LabTheme.typography.body.copy(
                    color = if (enabled) LabTheme.colors.text else LabTheme.colors.textDisabled,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
    }
}

private val labelStyle: TextStyle
    @Composable get() =
        LabTheme.typography.body.copy(
            color = LabTheme.colors.text,
            fontSize = 12.sp,
        )
