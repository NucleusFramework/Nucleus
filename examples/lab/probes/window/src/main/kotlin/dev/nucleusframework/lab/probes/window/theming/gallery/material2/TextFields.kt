package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun TextFieldsPage() {
    Page {
        Demo("Filled") {
            var text by remember { mutableStateOf("") }
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search") },
                placeholder = { Text("Type to search") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
            )
            FieldStates(outlined = false)
        }
        Demo("Outlined") {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
            )
            FieldStates(outlined = true)
        }
        Demo("Validation: an e-mail address") {
            var email by remember { mutableStateOf("not an address") }
            val invalid = email.isNotEmpty() && !email.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (invalid) "E-mail (invalid)" else "E-mail") },
                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
            )
            if (invalid) {
                Text(
                    "Enter an address like name@example.com",
                    color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption,
                )
            }
        }
        Demo("Password") {
            var password by remember { mutableStateOf("hunter2") }
            var visible by remember { mutableStateOf(false) }
            TextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "Hide" else "Show",
                        )
                    }
                },
                singleLine = true,
            )
        }
        Demo("Multi-line") {
            var notes by remember { mutableStateOf("First line\nSecond line\nThird line") }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 6,
            )
            StateLine("${notes.lines().size} lines · ${notes.length} characters")
        }
    }
}

/** Error and disabled variants side by side. */
@Composable
private fun FieldStates(outlined: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val error = Modifier.weight(1f)
        val disabled = Modifier.weight(1f)
        if (outlined) {
            OutlinedTextField("Wrong", {}, error, label = { Text("Error") }, isError = true, singleLine = true)
            OutlinedTextField("Read me", {}, disabled, label = { Text("Disabled") }, enabled = false)
        } else {
            TextField("Wrong", {}, error, label = { Text("Error") }, isError = true, singleLine = true)
            TextField("Read me", {}, disabled, label = { Text("Disabled") }, enabled = false)
        }
    }
}
