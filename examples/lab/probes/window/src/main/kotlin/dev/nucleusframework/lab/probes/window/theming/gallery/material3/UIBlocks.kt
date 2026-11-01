package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ParentSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        content()
    }
}

@Composable
internal fun ChildSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    content()
}
