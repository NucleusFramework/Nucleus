package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.globalhotkey.GlobalHotKeyManager
import dev.nucleusframework.nucleus.globalhotkey.HotKeyModifier
import dev.nucleusframework.nucleus.globalhotkey.MediaKey
import dev.nucleusframework.nucleus.globalhotkey.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.KeyEvent

private data class RegisteredHotKey(
    val handle: Long,
    val label: String,
)

@Suppress("FunctionNaming", "LongMethod")
@Composable
fun GlobalHotKeyScreen() {
    val registered = remember { mutableStateListOf<RegisteredHotKey>() }
    val events = remember { mutableStateListOf<String>() }
    var initialized by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Modifier toggles — avoid Ctrl+Alt+Fn on Linux (triggers VT switching)
    var useCtrl by remember { mutableStateOf(true) }
    var useAlt by remember { mutableStateOf(false) }
    var useShift by remember { mutableStateOf(true) }
    var useMeta by remember { mutableStateOf(false) }

    // Key input
    var keyText by remember { mutableStateOf("K") }

    DisposableEffect(Unit) {
        initialized = GlobalHotKeyManager.initialize()
        onDispose {
            GlobalHotKeyManager.shutdown()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Global Hotkeys",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            val statusText =
                when {
                    !GlobalHotKeyManager.isAvailable -> "Not available on this platform"
                    !initialized -> "Init failed: ${GlobalHotKeyManager.lastError}"
                    Platform.isWayland -> "Initialized (Wayland/Portal)"
                    else -> "Initialized"
                }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (initialized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Modifier checkboxes
            Text("Modifiers", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useCtrl, onCheckedChange = { useCtrl = it })
                    Text("Ctrl")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useAlt, onCheckedChange = { useAlt = it })
                    Text("Alt")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useShift, onCheckedChange = { useShift = it })
                    Text("Shift")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useMeta, onCheckedChange = { useMeta = it })
                    Text("Meta")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Key input + register
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it.uppercase() },
                    label = { Text("Key (e.g. F12, A, 1)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(0.3f),
                )

                Button(
                    onClick = {
                        val keyCode = resolveKeyCode(keyText) ?: return@Button
                        var mods = 0
                        val parts = mutableListOf<String>()
                        if (useCtrl) {
                            mods = mods + HotKeyModifier.CONTROL
                            parts += "Ctrl"
                        }
                        if (useAlt) {
                            mods = mods + HotKeyModifier.ALT
                            parts += "Alt"
                        }
                        if (useShift) {
                            mods = mods + HotKeyModifier.SHIFT
                            parts += "Shift"
                        }
                        if (useMeta) {
                            mods = mods + HotKeyModifier.META
                            parts += "Meta"
                        }
                        parts += keyText
                        val label = parts.joinToString("+")
                        scope.launch(Dispatchers.IO) {
                            val handle =
                                GlobalHotKeyManager.register(keyCode, mods) { _, _ ->
                                    events.add(0, "Hotkey pressed: $label")
                                    if (events.size > 50) events.removeAt(events.lastIndex)
                                }
                            withContext(Dispatchers.Main) {
                                if (handle >= 0) {
                                    registered += RegisteredHotKey(handle, label)
                                } else {
                                    events.add(0, "Failed to register $label: ${GlobalHotKeyManager.lastError}")
                                }
                            }
                        }
                    },
                    enabled = initialized,
                ) { Text("Register") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Media keys
            Text("Media Keys", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mk in MediaKey.entries) {
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val handle =
                                    GlobalHotKeyManager.register(mk) { _, _ ->
                                        events.add(0, "Media key: ${mk.name}")
                                        if (events.size > 50) events.removeAt(events.lastIndex)
                                    }
                                withContext(Dispatchers.Main) {
                                    if (handle >= 0) {
                                        registered += RegisteredHotKey(handle, mk.name)
                                    } else {
                                        events.add(0, "Failed: ${mk.name}: ${GlobalHotKeyManager.lastError}")
                                    }
                                }
                            }
                        },
                        enabled = initialized,
                    ) { Text(mk.name.replace("_", " ")) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Registered hotkeys + event log side by side
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Registered hotkeys
                Column(modifier = Modifier.weight(1f)) {
                    Text("Registered (${registered.size})", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(registered, key = { it.handle }) { hk ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(hk.label)
                                    Button(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                GlobalHotKeyManager.unregister(hk.handle)
                                                withContext(Dispatchers.Main) {
                                                    registered.removeAll { it.handle == hk.handle }
                                                }
                                            }
                                        },
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                            ),
                                    ) { Text("Remove") }
                                }
                            }
                        }
                    }
                }

                // Event log
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Events", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { events.clear() }) { Text("Clear") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(events) { event ->
                            Text(
                                text = event,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun resolveKeyCode(text: String): Int? {
    val upper = text.trim().uppercase()
    return when {
        upper.length == 1 && upper[0].isLetterOrDigit() -> KeyEvent.getExtendedKeyCodeForChar(upper[0].code)
        upper.startsWith("F") && upper.substring(1).toIntOrNull() != null -> {
            val fNum = upper.substring(1).toInt()
            if (fNum in 1..24) KeyEvent.VK_F1 + fNum - 1 else null
        }
        upper == "SPACE" -> KeyEvent.VK_SPACE
        upper == "ENTER" -> KeyEvent.VK_ENTER
        upper == "ESC" || upper == "ESCAPE" -> KeyEvent.VK_ESCAPE
        upper == "TAB" -> KeyEvent.VK_TAB
        upper == "DELETE" -> KeyEvent.VK_DELETE
        upper == "INSERT" -> KeyEvent.VK_INSERT
        upper == "HOME" -> KeyEvent.VK_HOME
        upper == "END" -> KeyEvent.VK_END
        upper == "PAGEUP" -> KeyEvent.VK_PAGE_UP
        upper == "PAGEDOWN" -> KeyEvent.VK_PAGE_DOWN
        upper == "UP" -> KeyEvent.VK_UP
        upper == "DOWN" -> KeyEvent.VK_DOWN
        upper == "LEFT" -> KeyEvent.VK_LEFT
        upper == "RIGHT" -> KeyEvent.VK_RIGHT
        upper == "PRINTSCREEN" -> KeyEvent.VK_PRINTSCREEN
        else -> null
    }
}
