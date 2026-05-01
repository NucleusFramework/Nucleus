package io.github.kdroidfilter.samplejni

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Frame

@Composable
fun ActionsTab(
    modifier: Modifier = Modifier,
    window: ComposeWindow,
    onLog: (String) -> Unit,
) {
    var titleInput by remember { mutableStateOf("JNI Backend Demo") }

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
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .width(320.dp),
            )
            ActionButton("Apply") {
                window.title = titleInput
                onLog("setTitle(\"$titleInput\")")
            }
        }

        SectionTitle("Window state")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Minimize") {
                window.extendedState = window.extendedState or Frame.ICONIFIED
                onLog("ICONIFIED")
            }
            ActionButton("Toggle Maximize") {
                val isMax = window.extendedState and Frame.MAXIMIZED_BOTH != 0
                window.extendedState = if (isMax) Frame.NORMAL else Frame.MAXIMIZED_BOTH
                onLog("setMaximized(${!isMax})")
            }
            ActionButton("Hide 2 s") {
                window.isVisible = false
                onLog("isVisible = false")
                Thread {
                    Thread.sleep(2_000)
                    java.awt.EventQueue.invokeLater {
                        window.isVisible = true
                        onLog("isVisible = true (auto)")
                    }
                }.start()
            }
        }

        SectionTitle("Close")
        ActionButton("requestClose()", accent = Color(0xFFFF7777)) {
            window.dispatchEvent(java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING))
            onLog("dispatch WINDOW_CLOSING")
        }

        Spacer(Modifier.height(8.dp))
        BasicText(
            "AWT-based backend (decorated-window-jni). Drag the title bar to move; double-click to maximize.",
            style = TextStyle(color = Color(0xFF7A8088), fontSize = 11.sp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text = text.uppercase(),
        style = TextStyle(
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
        modifier = Modifier
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
