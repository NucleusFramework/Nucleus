package io.github.kdroidfilter.samplejni

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.nucleus.application.NucleusWindow

@Composable
fun ActionsTab(
    modifier: Modifier = Modifier,
    window: NucleusWindow,
    currentTitle: String,
    onTitleChange: (String) -> Unit,
    onLog: (String) -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionTitle("Title")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = currentTitle,
                onValueChange = onTitleChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color(0xFF8AB4FF)),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .width(320.dp),
            )
            ActionButton("Apply") {
                onLog("setTitle(\"$currentTitle\")")
            }
        }

        SectionTitle("Window state")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Minimize") {
                window.setMinimized(true)
                onLog("setMinimized(true)")
            }
            ActionButton("Toggle Maximize") {
                val next = !window.isMaximized
                window.setMaximized(next)
                onLog("setMaximized($next)")
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

        SectionTitle("Close")
        ActionButton("requestClose()", accent = Color(0xFFFF7777)) {
            window.close()
            onLog("window.close()")
        }

        Spacer(Modifier.height(8.dp))
        BasicText(
            "Backend-agnostic window controls via NucleusWindow. Drag the title bar to move; double-click to maximize.",
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
