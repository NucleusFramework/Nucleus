package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun TextInputs() {
    ParentSection(title = "Text inputs") {
        ChildSection(title = "Text fields") { TextFields() }
    }
}

@Composable
private fun TextFields() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            var txt by remember { mutableStateOf("") }
            TextField(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                singleLine = true,
                value = txt,
                onValueChange = { txt = it },
                label = { Text("Filled") },
                supportingText = { Text("supporting text") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    IconButton(onClick = { txt = "" }) { Icon(Icons.Default.Close, null) }
                },
                placeholder = { Text("placeholder") },
            )
            Row {
                TextField(
                    modifier = Modifier.weight(1f).padding(8.dp),
                    singleLine = true,
                    value = txt,
                    onValueChange = { txt = it },
                    label = { Text("Filled", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    supportingText = { Text("error text") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { txt = "" }) { Icon(Icons.Default.Close, null) }
                    },
                    placeholder = { Text("placeholder", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    isError = true,
                )
                TextField(
                    modifier = Modifier.weight(1f).padding(8.dp),
                    singleLine = true,
                    value = txt,
                    onValueChange = { txt = it },
                    label = { Text("Disabled", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    supportingText = { Text("supporting text") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { txt = "" }) { Icon(Icons.Default.Close, null) }
                    },
                    placeholder = { Text("placeholder", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    enabled = false,
                )
            }
            var txt2 by remember { mutableStateOf("") }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                singleLine = true,
                value = txt2,
                onValueChange = { txt2 = it },
                label = { Text("Outlined") },
                supportingText = { Text("supporting text") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    IconButton(onClick = { txt2 = "" }) { Icon(Icons.Default.Close, null) }
                },
                placeholder = { Text("placeholder") },
            )
            Row {
                OutlinedTextField(
                    modifier = Modifier.weight(1f).padding(8.dp),
                    singleLine = true,
                    value = txt2,
                    onValueChange = { txt2 = it },
                    label = { Text("Outlined", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    supportingText = { Text("error text") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { txt2 = "" }) { Icon(Icons.Default.Close, null) }
                    },
                    placeholder = { Text("placeholder", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    isError = true,
                )
                OutlinedTextField(
                    modifier = Modifier.weight(1f).padding(8.dp),
                    singleLine = true,
                    value = txt2,
                    onValueChange = { txt2 = it },
                    label = { Text("Disabled", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    supportingText = { Text("supporting text") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { txt2 = "" }) { Icon(Icons.Default.Close, null) }
                    },
                    placeholder = { Text("placeholder", overflow = TextOverflow.Ellipsis, maxLines = 1) },
                    enabled = false,
                )
            }
        }
    }
}
