package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.application.NucleusWindow
import io.github.kdroidfilter.nucleus.taskbarprogress.TaskbarProgress
import io.github.kdroidfilter.nucleus.taskbarprogress.tao.hideTaskbarProgress
import io.github.kdroidfilter.nucleus.taskbarprogress.tao.requestTaskbarAttention
import io.github.kdroidfilter.nucleus.taskbarprogress.tao.setTaskbarProgress
import io.github.kdroidfilter.nucleus.taskbarprogress.tao.showTaskbarError
import io.github.kdroidfilter.nucleus.taskbarprogress.tao.showTaskbarIndeterminate
import io.github.kdroidfilter.nucleus.taskbarprogress.tao.showTaskbarPaused
import io.github.kdroidfilter.nucleus.taskbarprogress.tao.showTaskbarProgress
import io.github.kdroidfilter.nucleus.taskbarprogress.tao.stopTaskbarAttention
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ATTENTION_DELAY_MS = 1_000L

@Suppress("FunctionNaming", "LongMethod")
@Composable
fun TaskbarProgressScreen(window: NucleusWindow) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0.5f) }
    var currentState by remember { mutableStateOf(TaskbarProgress.State.NO_PROGRESS) }
    var attentionCountdown by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { window.hideTaskbarProgress() }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Taskbar Progress",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (TaskbarProgress.isAvailable()) "Available" else "Not available on this platform",
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (TaskbarProgress.isAvailable()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Progress: ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(0.6f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = progress,
                onValueChange = {
                    progress = it
                    when (currentState) {
                        TaskbarProgress.State.NORMAL,
                        TaskbarProgress.State.ERROR,
                        TaskbarProgress.State.PAUSED,
                        -> window.setTaskbarProgress(progress.toDouble())

                        else -> {
                            window.showTaskbarProgress(progress.toDouble())
                            currentState = TaskbarProgress.State.NORMAL
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(0.6f),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "State: $currentState",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    window.showTaskbarProgress(progress.toDouble())
                    currentState = TaskbarProgress.State.NORMAL
                }) { Text("Normal") }

                Button(
                    onClick = {
                        window.showTaskbarError(progress.toDouble())
                        currentState = TaskbarProgress.State.ERROR
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) { Text("Error") }

                Button(
                    onClick = {
                        window.showTaskbarPaused(progress.toDouble())
                        currentState = TaskbarProgress.State.PAUSED
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                        ),
                ) { Text("Paused") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    window.showTaskbarIndeterminate()
                    currentState = TaskbarProgress.State.INDETERMINATE
                }) { Text("Indeterminate") }

                OutlinedButton(onClick = {
                    window.hideTaskbarProgress()
                    currentState = TaskbarProgress.State.NO_PROGRESS
                }) { Text("Hide") }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Attention (3s delay — switch to another window)",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (attentionCountdown > 0) {
                Text(
                    text = "Flashing in ${attentionCountdown}s...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            for (i in 3 downTo 1) {
                                attentionCountdown = i
                                delay(ATTENTION_DELAY_MS)
                            }
                            attentionCountdown = 0
                            window.requestTaskbarAttention(TaskbarProgress.AttentionType.INFORMATIONAL)
                        }
                    },
                    enabled = attentionCountdown == 0,
                ) { Text("Informational") }

                Button(
                    onClick = {
                        scope.launch {
                            for (i in 3 downTo 1) {
                                attentionCountdown = i
                                delay(ATTENTION_DELAY_MS)
                            }
                            attentionCountdown = 0
                            window.requestTaskbarAttention(TaskbarProgress.AttentionType.CRITICAL)
                        }
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    enabled = attentionCountdown == 0,
                ) { Text("Critical") }

                OutlinedButton(onClick = {
                    window.stopTaskbarAttention()
                }) { Text("Stop") }
            }
        }
    }
}
