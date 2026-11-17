package dev.nucleusframework.tray.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class Screen {
    Screen1,
    Screen2,
}

@Composable
internal fun ScreenOne(
    onNavigate: () -> Unit,
    textVisible: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Welcome to Screen 1",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNavigate) {
            Text("Go to Screen 2")
        }
        Spacer(modifier = Modifier.height(24.dp))
        if (textVisible) {
            Text(
                "This is the additional text displayed based on tray selection.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun ScreenTwo(
    onNavigate: () -> Unit,
    textVisible: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Welcome to Screen 2",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNavigate) {
            Text("Go back to Screen 1")
        }
        Spacer(modifier = Modifier.height(24.dp))
        if (textVisible) {
            Text(
                "This is the additional text displayed based on tray selection.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
