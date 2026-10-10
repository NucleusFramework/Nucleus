package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Containment() {
    ParentSection("Containment") {
        ChildSection(title = "Cards") { CardsDemo() }
        ChildSection(title = "Dialog") { DialogsDemo() }
        ChildSection(title = "Dividers") { DividersDemo() }
    }
}

@Composable
private fun DividersDemo() {
    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp, 32.dp, 16.dp, 32.dp),
        ) {
            HorizontalDivider()
        }
    }
}

@Composable
private fun DialogsDemo() {
    val openAlertDialog = remember { mutableStateOf(false) }

    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            TextButton(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).weight(1f),
                onClick = { openAlertDialog.value = true },
                content = { Text("Show dialog") },
            )
        }
    }

    if (openAlertDialog.value) {
        AlertDialog(
            title = { Text(text = "What is a dialog?") },
            text = {
                Text(
                    text =
                        "A dialog is a type of modal window that appears in front of app content " +
                            "to provide critical information, or prompt for a decision to be made.",
                )
            },
            onDismissRequest = { openAlertDialog.value = false },
            confirmButton = {
                TextButton(onClick = { openAlertDialog.value = false }) { Text("Okay") }
            },
            dismissButton = {
                TextButton(onClick = { openAlertDialog.value = false }) { Text("Dismiss") }
            },
        )
    }
}

@Composable
private fun CardsDemo() {
    @Composable
    fun CardTemplate(
        title: String,
        elevation: androidx.compose.material3.CardElevation,
        colors: androidx.compose.material3.CardColors,
        border: BorderStroke? = null,
    ) {
        Card(
            modifier = Modifier.size(width = 115.dp, height = 100.dp),
            elevation = elevation,
            colors = colors,
            border = border,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(10.dp, 5.dp, 5.dp, 10.dp),
            ) {
                Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.BottomStart) {
                    Text(title, fontSize = 14.sp)
                }
                Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.TopEnd) {
                    IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                }
            }
        }
    }

    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CardTemplate(
                title = "Elevated",
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
                colors = CardDefaults.elevatedCardColors(),
            )
            androidx.compose.foundation.layout
                .Spacer(modifier = Modifier.width(16.dp))
            CardTemplate(
                title = "Filled",
                elevation = CardDefaults.cardElevation(),
                colors = CardDefaults.cardColors(),
            )
            androidx.compose.foundation.layout
                .Spacer(modifier = Modifier.width(16.dp))
            CardTemplate(
                title = "Outlined",
                elevation = CardDefaults.outlinedCardElevation(),
                colors = CardDefaults.outlinedCardColors(),
                border = BorderStroke(1.dp, Color.DarkGray),
            )
        }
    }
}
