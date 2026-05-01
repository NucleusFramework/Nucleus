package io.github.kdroidfilter.sampleshared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EventsTab(
    modifier: Modifier = Modifier,
    events: SnapshotStateList<String>,
) {
    Column(modifier = modifier.padding(20.dp)) {
        BasicText(
            text = "Events log (newest first)",
            style = TextStyle(
                color = Color(0xFF7A8088),
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            ),
        )

        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            if (events.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        text = "No events yet — interact with the app to see them appear here.",
                        style = TextStyle(color = Color(0xFF7A8088), fontSize = 13.sp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    items(events) { line ->
                        BasicText(
                            text = line,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            style = TextStyle(
                                color = Color(0xFFE6E6E6),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        }
    }
}
