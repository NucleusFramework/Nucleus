package io.github.kdroidfilter.sampleshared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Comprehensive accessibility test surface. Every interactive control here
 * is wired with explicit Compose semantics so the macOS NSAccessibility
 * projection can be exercised end-to-end via System Events / VoiceOver.
 *
 * Each section visually renders a counter or a state indicator that makes
 * the AX action's effect observable from outside (osascript test scripts
 * read these descriptions to assert success).
 */
@Composable
fun A11yTab(
    modifier: Modifier = Modifier,
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
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF12141A))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // ── Heading ────────────────────────────────────────────────────────
        BasicText(
            text = "Accessibility Test Surface",
            modifier = Modifier.semantics { heading() },
            style = TextStyle(
                color = Color(0xFFE6E6E6),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
        )

        // ── Button + counter (verifies AXPress flows through to Compose) ──
        Section("Button") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                A11yButton(
                    label = "Increment",
                    onClick = { clicks++ },
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
            )
        }

        // ── Checkbox (tri-state) ──────────────────────────────────────────
        Section("Checkbox") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (checkboxState) {
                                ToggleableState.On -> Color(0xFF34D399)
                                ToggleableState.Indeterminate -> Color(0xFFF59E0B)
                                ToggleableState.Off -> Color(0xFF374151)
                            },
                        )
                        .clickable {
                            checkboxState = when (checkboxState) {
                                ToggleableState.Off -> ToggleableState.On
                                ToggleableState.On -> ToggleableState.Indeterminate
                                ToggleableState.Indeterminate -> ToggleableState.Off
                            }
                        }
                        .semantics {
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
                    modifier = Modifier
                        .width(40.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (switchOn) Color(0xFF34D399) else Color(0xFF374151))
                        .clickable { switchOn = !switchOn }
                        .semantics {
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(
                                    1.dp,
                                    if (radioSelected == idx) Color(0xFF8AB4FF) else Color(0xFF6B7280),
                                    CircleShape,
                                )
                                .background(
                                    if (radioSelected == idx) Color(0xFF8AB4FF) else Color.Transparent,
                                )
                                .clickable { radioSelected = idx }
                                .semantics {
                                    role = Role.RadioButton
                                    toggleableState = if (radioSelected == idx) ToggleableState.On else ToggleableState.Off
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
                    modifier = Modifier
                        .width(160.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1F2937))
                        .semantics {
                            contentDescription = "Volume"
                            progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                                current = sliderValue,
                                range = sliderRange,
                            )
                            setProgress { newValue ->
                                sliderValue = newValue.coerceIn(sliderRange.start, sliderRange.endInclusive)
                                true
                            }
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(sliderValue)
                            .height(20.dp)
                            .background(Color(0xFF8AB4FF), RoundedCornerShape(10.dp)),
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
                    modifier = Modifier
                        .width(220.dp)
                        .background(Color(0xFF1F2937), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF374151), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    BasicTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        singleLine = true,
                        textStyle = labelStyle,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF8AB4FF)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                BasicText(
                    text = "len=${textValue.text.length} sel=${textValue.selection.start}..${textValue.selection.end}",
                    modifier = Modifier.semantics {
                        contentDescription = "TextField status: text='${textValue.text}'"
                    },
                    style = labelStyle,
                )
            }
        }

        // ── Live region (announces on text change) ────────────────────────
        Section("Live region (assertive)") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                A11yButton(
                    label = "Update status",
                    onClick = {
                        val ts = java.time.LocalTime.now().withNano(0)
                        status = "Status updated at $ts"
                    },
                )
                BasicText(
                    text = status,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = status
                    },
                    style = labelStyle,
                )
            }
        }

        // ── Modal dialog ──────────────────────────────────────────────────
        Section("Modal dialog") {
            A11yButton(
                label = "Open dialog",
                onClick = { dialogOpen = true },
            )
        }

        Spacer(Modifier.height(20.dp))
    }

    if (dialogOpen) {
        Dialog(onDismissRequest = { dialogOpen = false }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1F2937), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(24.dp)
                    .semantics {
                        // IsDialog is auto-set by Compose's Dialog, but we
                        // also expose a Dismiss action so VoiceOver can close
                        // via VO+Esc.
                        this[SemanticsProperties.IsDialog] = Unit
                        dismiss { dialogOpen = false; true }
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BasicText(
                    text = "Modal dialog",
                    modifier = Modifier.semantics { heading() },
                    style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
                BasicText(
                    text = "VoiceOver users press VO+Esc to dismiss.",
                    style = labelStyle,
                )
                A11yButton(label = "Close", onClick = { dialogOpen = false })
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = TextStyle(
                color = Color(0xFF9CA3AF),
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
) {
    val baseModifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(if (enabled) Color(0xFF1F2937) else Color(0xFF11151B))
        .padding(horizontal = 12.dp, vertical = 6.dp)
    val finalModifier = if (enabled) {
        baseModifier
            .clickable { onClick() }
            .semantics { role = Role.Button }
    } else {
        baseModifier.semantics {
            role = Role.Button
            this[androidx.compose.ui.semantics.SemanticsProperties.Disabled] = Unit
        }
    }
    Box(modifier = finalModifier) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (enabled) Color(0xFFE6E6E6) else Color(0xFF6B7280),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

private val labelStyle = TextStyle(
    color = Color(0xFFCBD5E1),
    fontSize = 12.sp,
)
