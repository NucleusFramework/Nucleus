package dev.nucleusframework.satellitedemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.SatelliteScope
import kotlin.math.roundToInt

private val Tools = listOf("Move", "Brush", "Eraser", "Fill", "Text", "Crop", "Lasso", "Zoom")

/**
 * The Tools palette: the GIMP-style toolbox that motivates satellites. Its
 * selection and brush size are `rememberSaveable`, which is what lets them
 * survive the trip from a floating window into a dock panel and back.
 */
@Composable
fun ToolsContent(scope: SatelliteScope) {
    var tool by rememberSaveable { mutableStateOf(Tools.first()) }
    var brushSize by rememberSaveable { mutableFloatStateOf(12f) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (scope.isDocked) "Docked palette" else "Floating palette",
            style = MaterialTheme.typography.labelLarge,
        )
        for (name in Tools) {
            FilterChip(
                selected = tool == name,
                onClick = { tool = name },
                label = { Text(name) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider()
        Text("Brush size: ${brushSize.roundToInt()} px", style = MaterialTheme.typography.bodySmall)
        Slider(value = brushSize, onValueChange = { brushSize = it }, valueRange = 1f..64f)
        Text(
            "Selected tool and brush size are rememberSaveable: dock and undock this " +
                "palette, they stay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
