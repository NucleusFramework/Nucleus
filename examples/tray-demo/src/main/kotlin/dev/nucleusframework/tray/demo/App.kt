package dev.nucleusframework.tray.demo

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import kotlin.system.exitProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App(
    textVisible: Boolean,
    alwaysShowTray: Boolean,
    hideOnClose: Boolean,
    onToggleChange: (Boolean, Boolean) -> Unit
) {
    var currentScreen by remember { mutableStateOf(Screen.Screen1) }
    
    // Automatically detect system theme
    val isDarkTheme = isSystemInDarkMode()

    // Material3 Theme with dark mode support
    MaterialTheme(
        colorScheme = if (isDarkTheme) {
            darkColorScheme()
        } else {
            lightColorScheme()
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Compose Tray App") },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            // Always Show Tray Toggle
                            Text("Always Show Tray")
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = alwaysShowTray,
                                onCheckedChange = { isChecked ->
                                    onToggleChange(isChecked, hideOnClose)
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            // Hide on Close Toggle
                            Text("Hide on Close")
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = hideOnClose,
                                onCheckedChange = { isChecked ->
                                    onToggleChange(alwaysShowTray, isChecked)
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            // Exit Button
                            Button(
                                onClick = { exitProcess(0) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Exit")
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        when (currentScreen) {
                            Screen.Screen1 -> ScreenOne(
                                onNavigate = { currentScreen = Screen.Screen2 },
                                textVisible = textVisible
                            )

                            Screen.Screen2 -> ScreenTwo(
                                onNavigate = { currentScreen = Screen.Screen1 },
                                textVisible = textVisible
                            )
                        }
                    }
                }
            }
        }
    }
}