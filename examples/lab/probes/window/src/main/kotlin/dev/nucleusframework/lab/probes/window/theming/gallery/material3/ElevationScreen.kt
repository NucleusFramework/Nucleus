package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class ElevationItem(
    val level: Int,
    val elevation: Int,
    val overlay: Float,
    val withShadow: Boolean = true,
    val withSurface: Boolean = true,
) {
    val shadowElevation = if (withShadow) elevation else 0
    val surfaceElevation = if (withSurface) elevation else 0
}

@Composable
internal fun ElevationScreen() {
    val items =
        listOf(
            ElevationItem(0, 0, 0f),
            ElevationItem(1, 1, 0.05f),
            ElevationItem(2, 3, 0.08f),
            ElevationItem(3, 6, 0.11f),
            ElevationItem(4, 8, 0.12f),
            ElevationItem(5, 12, 0.14f),
        )
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
    ) {
        Text(
            modifier = Modifier.padding(8.dp),
            text = "Surface Tint Color Only",
            style = MaterialTheme.typography.titleLarge,
        )
        NonlazyGrid(
            modifier = Modifier.padding(bottom = 16.dp),
            columns = 6,
            items = items.map { it.copy(withShadow = false) },
        ) { ElevationCard(it) }
        Text(
            modifier = Modifier.padding(8.dp),
            text = "Surface Tint Color and Shadow Color",
            style = MaterialTheme.typography.titleLarge,
        )
        NonlazyGrid(
            modifier = Modifier.padding(bottom = 16.dp),
            columns = 6,
            items = items,
        ) { ElevationCard(it) }
        Text(
            modifier = Modifier.padding(8.dp),
            text = "Surface Shadow Color Only",
            style = MaterialTheme.typography.titleLarge,
        )
        NonlazyGrid(
            modifier = Modifier.padding(bottom = 16.dp),
            columns = 6,
            items = items.map { it.copy(withSurface = false) },
        ) { ElevationCard(it) }
    }
}

@Composable
private fun ElevationCard(item: ElevationItem) {
    Surface(
        modifier =
            Modifier
                .aspectRatio(1f)
                .padding(8.dp),
        tonalElevation = item.surfaceElevation.dp,
        shadowElevation = item.shadowElevation.dp,
        shape = RoundedCornerShape(4.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Text(style = MaterialTheme.typography.labelMedium, text = "Level ${item.level}")
            Text(style = MaterialTheme.typography.labelMedium, text = "${item.elevation} dp")
            Spacer(modifier = Modifier.weight(1f))
            Text(
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                text = "${(item.overlay * 100).toInt()}%",
            )
        }
    }
}
