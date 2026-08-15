package dev.nucleusframework.sampletao

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.application.spellcheck.SpellcheckContextMenu
import dev.nucleusframework.spellcheck.SpellChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SpellcheckTab(modifier: Modifier = Modifier) {
    var singleLine by remember { mutableStateOf("helo world") }
    var multiLine by remember {
        mutableStateOf("I recieve the teh package tommorow.\nPlease chek the adress.")
    }
    var material by remember { mutableStateOf("helo material") }
    var available by remember { mutableStateOf(SpellChecker.isAvailable) }
    var dictionary by remember { mutableStateOf(SpellChecker.sessionIfReady?.dictionaryTag ?: "—") }
    LaunchedEffect(Unit) {
        val session = withContext(Dispatchers.IO) { SpellChecker.ensureSession() }
        available = session.isAvailable
        dictionary = session.dictionaryTag ?: "—"
    }

    SpellcheckTabBody(
        modifier = modifier,
        available = available,
        dictionary = dictionary,
        singleLine = singleLine,
        onSingleLineChange = { singleLine = it },
        multiLine = multiLine,
        onMultiLineChange = { multiLine = it },
        material = material,
        onMaterialChange = { material = it },
    )
}

@Composable
private fun SpellcheckTabBody(
    modifier: Modifier,
    available: Boolean,
    dictionary: String,
    singleLine: String,
    onSingleLineChange: (String) -> Unit,
    multiLine: String,
    onMultiLineChange: (String) -> Unit,
    material: String,
    onMaterialChange: (String) -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(
            text = "Spellcheck",
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        )
        BasicText(
            text =
                if (available) {
                    "Hunspell ready ($dictionary). Misspellings get a red wave; " +
                        "right-click a bad word for suggestions / add to dictionary."
                } else {
                    "Hunspell not available on this machine (Linux + system .aff/.dic). " +
                        "Type anyway — the wrap is a no-op."
                },
            style = TextStyle(color = Color(0xFFA0A4B0), fontSize = 13.sp),
        )

        FieldLabel("Single line")
        DemoField(
            value = singleLine,
            onValueChange = onSingleLineChange,
            singleLine = true,
            testTag = "spellcheck-single",
        )

        FieldLabel("Multiline")
        DemoField(
            value = multiLine,
            onValueChange = onMultiLineChange,
            singleLine = false,
            testTag = "spellcheck-multi",
            modifier = Modifier.heightIn(min = 120.dp),
        )

        FieldLabel("Material TextField")
        MaterialDemoField(
            value = material,
            onValueChange = onMaterialChange,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    BasicText(
        text = text,
        style = TextStyle(color = Color(0xFF8AB4FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
private fun DemoField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    SpellcheckContextMenu(text = value, onTextChange = onValueChange) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color(0xFF8AB4FF)),
            modifier =
                modifier
                    .testTag(testTag)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MaterialDemoField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    MaterialTheme(
        colors =
            darkColors(
                primary = Color(0xFF8AB4FF),
                onPrimary = Color(0xFF0F1115),
                surface = Color(0xFF1A1D24),
                onSurface = Color.White,
                background = Color(0xFF0F1115),
                onBackground = Color.White,
            ),
    ) {
        SpellcheckContextMenu(text = value, onTextChange = onValueChange) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text("Material") },
                colors =
                    TextFieldDefaults.textFieldColors(
                        textColor = Color.White,
                        cursorColor = Color(0xFF8AB4FF),
                        focusedIndicatorColor = Color(0xFF8AB4FF),
                        backgroundColor = Color.White.copy(alpha = 0.06f),
                    ),
                modifier =
                    Modifier
                        .testTag("spellcheck-material")
                        .fillMaxWidth(),
            )
        }
    }
}
