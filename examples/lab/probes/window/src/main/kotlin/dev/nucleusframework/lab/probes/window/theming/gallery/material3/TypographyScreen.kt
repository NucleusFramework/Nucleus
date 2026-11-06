package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun TypographyScreen() {
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
    ) {
        Text(modifier = Modifier.padding(8.dp), text = "Display Large", style = MaterialTheme.typography.displayLarge)
        Text(modifier = Modifier.padding(8.dp), text = "Display Medium", style = MaterialTheme.typography.displayMedium)
        Text(modifier = Modifier.padding(8.dp), text = "Display Small", style = MaterialTheme.typography.displaySmall)
        Text(modifier = Modifier.padding(8.dp), text = "Headline Large", style = MaterialTheme.typography.headlineLarge)
        Text(
            modifier = Modifier.padding(8.dp),
            text = "Headline Medium",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(modifier = Modifier.padding(8.dp), text = "Headline Small", style = MaterialTheme.typography.headlineSmall)
        Text(modifier = Modifier.padding(8.dp), text = "Title Large", style = MaterialTheme.typography.titleLarge)
        Text(modifier = Modifier.padding(8.dp), text = "Title Medium", style = MaterialTheme.typography.titleMedium)
        Text(modifier = Modifier.padding(8.dp), text = "Title Small", style = MaterialTheme.typography.titleSmall)
        Text(modifier = Modifier.padding(8.dp), text = "Label Large", style = MaterialTheme.typography.labelLarge)
        Text(modifier = Modifier.padding(8.dp), text = "Label Medium", style = MaterialTheme.typography.labelMedium)
        Text(modifier = Modifier.padding(8.dp), text = "Label Small", style = MaterialTheme.typography.labelSmall)
        Text(modifier = Modifier.padding(8.dp), text = "Body Large", style = MaterialTheme.typography.bodyLarge)
        Text(modifier = Modifier.padding(8.dp), text = "Body Medium", style = MaterialTheme.typography.bodyMedium)
        Text(modifier = Modifier.padding(8.dp), text = "Body Small", style = MaterialTheme.typography.bodySmall)
    }
}
