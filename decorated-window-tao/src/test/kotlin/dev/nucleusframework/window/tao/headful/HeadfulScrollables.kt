package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger

/*
 * Scrollable fixtures shared by the scroll headful cases (Linux discrete wheel,
 * macOS trackpad). Both publish the live scroll offset and its maximum into
 * the atomics so a driver can await overflow and observe movement.
 */

private const val CELL_COUNT = 80
private const val CELL_SIZE_DP = 24

@Composable
internal fun ScrollableColumn(
    scrollPx: AtomicInteger,
    scrollMax: AtomicInteger,
) {
    val state = rememberScrollState()
    scrollPx.set(state.value)
    scrollMax.set(state.maxValue)
    Column(Modifier.fillMaxSize().verticalScroll(state)) {
        repeat(CELL_COUNT) { i ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(CELL_SIZE_DP.dp)
                    .background(if (i % 2 == 0) Color.DarkGray else Color.Gray),
            )
        }
    }
}

@Composable
internal fun ScrollableRow(
    scrollPx: AtomicInteger,
    scrollMax: AtomicInteger,
) {
    val state = rememberScrollState()
    scrollPx.set(state.value)
    scrollMax.set(state.maxValue)
    Row(Modifier.fillMaxSize().horizontalScroll(state)) {
        repeat(CELL_COUNT) { i ->
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(CELL_SIZE_DP.dp)
                    .background(if (i % 2 == 0) Color.DarkGray else Color.Gray),
            )
        }
    }
}
