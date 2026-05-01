package io.github.kdroidfilter.sampletao

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer
import io.github.kdroidfilter.nucleus.window.tao.DecoratedWindow
import io.github.kdroidfilter.nucleus.window.tao.TitleBar
import io.github.kdroidfilter.nucleus.window.tao.taoApplication

private enum class Tab(val label: String) {
    Demo("Demo"),
    Scroll("Scroll"),
    Actions("Window actions"),
    Events("Events"),
}

fun main() {
    GraalVmInitializer.initialize()
    runApp()
}

private fun runApp() = taoApplication {
    DecoratedWindow(
        onCloseRequest = ::exitApplication,
        title = "Tao Backend Demo",
        width = 1024.0,
        height = 720.0,
    ) {
        val taoWindow = window
        var clicks by remember { mutableStateOf(0) }
        val enabledBlobs = remember { mutableStateListOf(true, true, true, true) }
        var selectedTab by remember { mutableStateOf(Tab.Demo) }
        val events = remember { mutableStateListOf<String>() }

        TitleBar(height = 36.dp) { state ->
            Row(
                modifier = Modifier.align(Alignment.Start).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(if (state.isActive) Color(0xFF34D399) else Color(0xFF6B7280)),
                )
                BasicText(
                    text = if (state.isActive) "Live" else "Inactive",
                    style = TextStyle(color = Color(0xFFA0A4B0), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
            }

            BasicText(
                text = title,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = TextStyle(
                    color = if (state.isActive) Color(0xFFE6E6E6) else Color(0xFFE6E6E6).copy(alpha = 0.5f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                ),
            )

            Row(
                modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PALETTE.forEachIndexed { idx, color ->
                    Box(
                        modifier = Modifier.size(14.dp).clip(CircleShape)
                            .background(if (enabledBlobs[idx]) color else color.copy(alpha = 0.18f))
                            .border(1.dp, if (enabledBlobs[idx]) color.copy(alpha = 0.4f) else Color.Transparent, CircleShape)
                            .clickable { enabledBlobs[idx] = !enabledBlobs[idx] },
                    )
                }
                Box(modifier = Modifier.size(width = 8.dp, height = 16.dp))
                BasicText(
                    text = "Clear",
                    style = TextStyle(color = Color(0xFF8AB4FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { clicks = 0; events.clear() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxSize().background(Color(0xFF0F1115))) {
            TabBar(selectedTab, onSelect = { selectedTab = it })
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                when (selectedTab) {
                    Tab.Demo -> FancyDemo(
                        modifier = Modifier.fillMaxSize(),
                        clicks = clicks,
                        onClick = { clicks++; logEvent(events, "click @ demo (#$clicks)") },
                        enabledBlobs = enabledBlobs,
                    )
                    Tab.Scroll -> ScrollTab(modifier = Modifier.fillMaxSize())
                    Tab.Actions -> ActionsTab(
                        modifier = Modifier.fillMaxSize(),
                        window = taoWindow,
                        onLog = { logEvent(events, it) },
                    )
                    Tab.Events -> EventsTab(modifier = Modifier.fillMaxSize(), events = events)
                }
            }
        }
    }
}

@Composable
private fun TabBar(
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
                    style = TextStyle(
                        color = if (isSelected) Color(0xFF8AB4FF) else Color(0xFFA0A4B0),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

internal fun logEvent(events: SnapshotStateList<String>, line: String) {
    val ts = java.time.LocalTime.now().withNano(0).toString()
    events.add(0, "$ts  $line")
    if (events.size > 200) events.removeAt(events.lastIndex)
}
