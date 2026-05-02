package io.github.kdroidfilter.samplejni

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.application.DecoratedWindow
import io.github.kdroidfilter.nucleus.application.NucleusBackend
import io.github.kdroidfilter.nucleus.application.nucleusApplication
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.TitleBar
import io.github.kdroidfilter.nucleus.window.macOSLargeCornerRadius
import io.github.kdroidfilter.nucleus.window.styling.TitleBarColors
import io.github.kdroidfilter.nucleus.window.styling.TitleBarMetrics
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.sampleshared.EventsTab
import io.github.kdroidfilter.sampleshared.FancyDemo
import io.github.kdroidfilter.sampleshared.PALETTE
import io.github.kdroidfilter.sampleshared.ScrollTab
import io.github.kdroidfilter.sampleshared.Tab
import io.github.kdroidfilter.sampleshared.TabBar
import io.github.kdroidfilter.sampleshared.logEvent

fun main() = nucleusApplication(backend = NucleusBackend.Awt) {
    val state = rememberWindowState(width = 1024.dp, height = 720.dp)

    val titleBarStyle = TitleBarStyle(
        colors = TitleBarColors(
            background = Color(0xFF1A1D24),
            inactiveBackground = Color(0xFF15181D),
            content = Color(0xFFE6E6E6),
            border = Color.Transparent,
        ),
        metrics = TitleBarMetrics(height = 36.dp),
    )

    var title by remember { mutableStateOf("JNI Backend Demo") }

    NucleusDecoratedWindowTheme(isDark = true, titleBarStyle = titleBarStyle) {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = state,
            title = title,
            minimumSize = DpSize(640.dp, 400.dp),
        ) {
            var clicks by remember { mutableStateOf(0) }
            val enabledBlobs = remember { mutableStateListOf(true, true, true, true) }
            var selectedTab by remember { mutableStateOf(Tab.Demo) }
            val events = remember { mutableStateListOf<String>() }

            TitleBar(modifier = Modifier.macOSLargeCornerRadius()) { state ->
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
                                .border(
                                    1.dp,
                                    if (enabledBlobs[idx]) color.copy(alpha = 0.4f) else Color.Transparent,
                                    CircleShape,
                                )
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

            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115))) {
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
                            window = nucleusWindow,
                            currentTitle = title,
                            onTitleChange = { title = it },
                            onLog = { logEvent(events, it) },
                        )
                        Tab.Events -> EventsTab(modifier = Modifier.fillMaxSize(), events = events)
                        else -> {}
                    }
                }
            }
        }
    }
}
