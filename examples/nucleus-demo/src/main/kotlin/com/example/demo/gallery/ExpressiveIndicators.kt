@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.example.demo.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ExpressiveIndicators() {
    ParentSection(title = "Expressive indicators") {
        ChildSection(title = "Loading indicators") { LoadingIndicatorsDemo() }
        ChildSection(title = "Wavy progress") { WavyProgressDemo() }
    }
}

@Composable
private fun LoadingIndicatorsDemo() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Indeterminate", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingIndicator()
                ContainedLoadingIndicator()
            }
            Text("Determinate (70%)", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingIndicator(progress = { 0.7f })
                ContainedLoadingIndicator(progress = { 0.7f })
            }
        }
    }
}

@Composable
private fun WavyProgressDemo() {
    var animated by remember { mutableStateOf(true) }
    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { animated = !animated }) {
                Icon(
                    if (animated) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
            }
            Spacer(Modifier.size(16.dp))
            if (animated) {
                CircularWavyProgressIndicator()
                Spacer(Modifier.size(16.dp))
                LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
            } else {
                CircularWavyProgressIndicator(progress = { 0.7f })
                Spacer(Modifier.size(16.dp))
                LinearWavyProgressIndicator(progress = { 0.7f }, modifier = Modifier.weight(1f))
            }
        }
    }
}
