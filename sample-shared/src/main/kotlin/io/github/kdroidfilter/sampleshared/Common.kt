package io.github.kdroidfilter.sampleshared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(
    val label: String,
) {
    Demo("Demo"),
    Scroll("Scroll"),
    Zoom("Zoom"),
    Actions("Window actions"),
    A11y("A11y"),
    Complex("Complex"),
    Events("Events"),
}

@Composable
fun TabBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).background(Color(0xFF15181D)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier.height(40.dp).clickable { onSelect(tab) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = tab.label,
                    style =
                        TextStyle(
                            color = if (isSelected) Color(0xFF8AB4FF) else Color(0xFFA0A4B0),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                )
            }
        }
    }
}

fun logEvent(
    events: SnapshotStateList<String>,
    text: String,
) {
    val time =
        java.time.LocalTime
            .now()
            .withNano(0)
    events.add(0, "[$time] $text")
    if (events.size > 200) events.removeRange(200, events.size)
}
