package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory

private fun hue(index: Int) = Color.hsv((index * 37f) % 360f, 0.45f, 0.85f)

@OptIn(ExperimentalFoundationApi::class)
val ListSamples: List<SampleEntry> =
    samples(SampleCategory.Lists) {
        sample(
            "lazy-column",
            "LazyColumn, 10 000 rows, sticky headers",
            "Wheel and trackpad scroll smoothly; the group header sticks to the top; the first-visible readout follows; no blank rows during a fling.",
        ) {
            val state = rememberLazyListState()
            val first by remember { derivedStateOf { state.firstVisibleItemIndex } }
            Column {
                Text("first visible: $first", style = LabTheme.typography.small)
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(260.dp)) {
                    for (group in 0 until 100) {
                        stickyHeader(key = "h$group") {
                            Text(
                                "Group $group",
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        LabTheme.colors.selection,
                                    ).padding(6.dp),
                                style = LabTheme.typography.heading,
                            )
                        }
                        items(100, key = { "$group-$it" }) {
                            Text("Row ${group * 100 + it}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
        sample(
            "lazy-row-grid",
            "LazyRow & LazyVerticalGrid",
            "The row scrolls horizontally (shift+wheel, trackpad); the adaptive grid re-flows its columns when the window is resized.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0 until 60).toList()) {
                        Box(
                            Modifier.size(64.dp).background(hue(it), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$it")
                        }
                    }
                }
                LazyVerticalGrid(
                    GridCells.Adaptive(72.dp),
                    Modifier.height(200.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(120) {
                        Box(
                            Modifier.height(48.dp).background(hue(it + 7), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$it")
                        }
                    }
                }
            }
        }
        sample(
            "pager",
            "HorizontalPager",
            "Swipe / drag between ten pages; it snaps to a page; the indicator follows; with RTL on, page 1 is on the right.",
        ) {
            val pager = rememberPagerState { 10 }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalPager(pager, Modifier.fillMaxWidth().height(160.dp), pageSpacing = 12.dp) { page ->
                    Box(
                        Modifier.fillMaxSize().background(hue(page * 3), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Page ${page + 1}", style = LabTheme.typography.title.copy(fontSize = 28.sp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(10) { page ->
                        val dot = if (page == pager.currentPage) LabTheme.colors.accent else Color.Gray
                        Box(Modifier.size(8.dp).background(dot, RoundedCornerShape(50)))
                    }
                }
            }
        }
        sample(
            "nested",
            "Nested scrolling",
            "Scrolling inside the inner horizontal strip moves only the strip; scrolling elsewhere scrolls the outer list.",
        ) {
            LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
                items((0 until 30).toList()) { row ->
                    if (row % 6 == 3) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            repeat(30) { Box(Modifier.size(56.dp).background(hue(it), RoundedCornerShape(6.dp))) }
                        }
                    } else {
                        Text("Outer row $row", Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
