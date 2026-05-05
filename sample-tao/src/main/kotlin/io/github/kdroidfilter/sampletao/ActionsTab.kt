package io.github.kdroidfilter.sampletao

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import io.github.kdroidfilter.nucleus.window.tao.TaoWindow

@Composable
fun ActionsTab(
    modifier: Modifier = Modifier,
    window: TaoWindow,
    placement: WindowPlacement,
    onPlacementChange: (WindowPlacement) -> Unit,
    onLog: (String) -> Unit,
    onOpenChildWindow: (enabled: Boolean, focusable: Boolean) -> Unit,
) {
    var titleInput by remember { mutableStateOf("Tao Backend Demo") }
    var alwaysOnTop by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionTitle("Title")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = titleInput,
                onValueChange = { titleInput = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color(0xFF8AB4FF)),
                modifier =
                    Modifier
                        .testTag("title-input")
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .width(320.dp),
            )
            ActionButton("Apply") {
                window.setTitle(titleInput)
                onLog("setTitle(\"$titleInput\")")
            }
            BasicTextField(
                value = "read-only sample",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFFB0B5BD), fontSize = 14.sp),
                modifier =
                    Modifier
                        .testTag("readonly-input")
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .width(180.dp),
            )
        }

        SectionTitle("Window state")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Minimize") {
                window.minimize()
                onLog("minimize()")
            }
            ActionButton("Toggle Maximize") {
                val newState = !window.isMaximized
                window.setMaximized(newState)
                onLog("setMaximized($newState)")
            }
            ActionButton("Hide 2 s") {
                window.hide()
                onLog("hide()")
                Thread {
                    Thread.sleep(2_000)
                    window.show()
                    onLog("show() (auto)")
                }.start()
            }
        }

        SectionTitle("Placement (via WindowState)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                label = "Floating" + if (placement == WindowPlacement.Floating) " ✓" else "",
            ) {
                onPlacementChange(WindowPlacement.Floating)
                onLog("placement = Floating")
            }
            ActionButton(
                label = "Maximized" + if (placement == WindowPlacement.Maximized) " ✓" else "",
            ) {
                onPlacementChange(WindowPlacement.Maximized)
                onLog("placement = Maximized")
            }
            ActionButton(
                label = "Fullscreen" + if (placement == WindowPlacement.Fullscreen) " ✓" else "",
            ) {
                onPlacementChange(WindowPlacement.Fullscreen)
                onLog("placement = Fullscreen")
            }
        }

        SectionTitle("Always on top")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        alwaysOnTop = !alwaysOnTop
                        window.setAlwaysOnTop(alwaysOnTop)
                        onLog("setAlwaysOnTop($alwaysOnTop)")
                    }.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (alwaysOnTop) Color(0xFF8AB4FF) else Color.White.copy(alpha = 0.06f))
                        .border(
                            1.dp,
                            if (alwaysOnTop) Color(0xFF8AB4FF) else Color.White.copy(alpha = 0.2f),
                            RoundedCornerShape(4.dp),
                        ).padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = if (alwaysOnTop) "✓" else " ",
                    style = TextStyle(color = Color(0xFF0F1115), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.width(14.dp),
                )
            }
            BasicText(
                text = "Keep window above all others",
                style = TextStyle(color = Color(0xFFB7B9C4), fontSize = 13.sp),
            )
        }

        SectionTitle("Drag")
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // dragWindow() must be called *during* a press — fire on
                            // the down event, not on click release.
                            awaitFirstDown(requireUnconsumed = false)
                            window.dragWindow()
                            onLog("dragWindow() — release the click to stop")
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                "Click + hold to drag the window from inside the body",
                style = TextStyle(color = Color(0xFFB7B9C4), fontSize = 12.sp),
            )
        }

        SectionTitle("enabled / focusable (open child window)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("enabled = false") {
                onOpenChildWindow(false, true)
            }
            ActionButton("focusable = false") {
                onOpenChildWindow(true, false)
            }
            ActionButton("both = false") {
                onOpenChildWindow(false, false)
            }
        }

        SectionTitle("Close")
        ActionButton("requestClose()", accent = Color(0xFFFF7777)) {
            window.requestClose()
            onLog("requestClose()")
        }

        Spacer(Modifier.height(8.dp))
        BasicText(
            "Cmd-Q closes the whole app via NSEvent monitor (in TaoApplication)",
            style = TextStyle(color = Color(0xFF7A8088), fontSize = 11.sp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text = text.uppercase(),
        style =
            TextStyle(
                color = Color(0xFF7A8088),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            ),
    )
}

@Composable
private fun ActionButton(
    label: String,
    accent: Color = Color(0xFF8AB4FF),
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.12f))
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}
