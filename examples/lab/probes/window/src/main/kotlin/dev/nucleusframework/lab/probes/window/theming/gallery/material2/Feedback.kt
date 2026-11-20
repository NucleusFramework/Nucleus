package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Snackbar
import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.SnackbarResult
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun FeedbackPage(snackbarHostState: SnackbarHostState) {
    Page {
        Demo("Snackbars in the scaffold's host") {
            val scope = rememberCoroutineScope()
            var outcome by remember { mutableStateOf("none") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { scope.launch { snackbarHostState.showSnackbar("Message archived") } }) {
                    Text("Simple")
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        val result =
                            snackbarHostState.showSnackbar(
                                "Conversation deleted",
                                actionLabel = "UNDO",
                                duration = SnackbarDuration.Long,
                            )
                        outcome = if (result == SnackbarResult.ActionPerformed) "undone" else "dismissed"
                    }
                }) { Text("With action") }
            }
            StateLine("Last snackbar: $outcome")
        }
        Demo("Snackbar styles (static)") {
            Snackbar { Text("Single-line snackbar") }
            Snackbar(action = { TextButton(onClick = {}) { Text("ACTION") } }) { Text("Snackbar with an action") }
            Snackbar(
                actionOnNewLine = true,
                action = { TextButton(onClick = {}) { Text("LONGER ACTION") } },
            ) { Text("A two-line snackbar puts a long action on a line of its own, under the message text.") }
        }
        Demo("Progress indicators") {
            val transition = rememberInfiniteTransition()
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 2400), RepeatMode.Restart),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                CircularProgressIndicator(progress = progress)
                CircularProgressIndicator(color = MaterialTheme.colors.secondary, strokeWidth = 8.dp)
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            LinearProgressIndicator(
                progress = 0.35f,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colors.secondary,
            )
        }
        Demo("Badges") {
            var mail by remember { mutableIntStateOf(3) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { mail++ }) {
                    BadgedBox(badge = { Badge { Text("$mail") } }) { Icon(Icons.Filled.Mail, "Mail") }
                }
                IconButton(onClick = {}) {
                    BadgedBox(badge = { Badge() }) { Icon(Icons.Filled.Notifications, "Notifications") }
                }
                IconButton(onClick = {}) {
                    BadgedBox(badge = { Badge { Text("99+") } }) { Icon(Icons.Filled.ShoppingCart, "Cart") }
                }
                TextButton(onClick = { mail = 0 }) { Text("Clear mail") }
            }
        }
    }
}
