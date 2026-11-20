package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory

val TextSamples: List<SampleEntry> =
    samples(SampleCategory.Text) {
        sample("ramp", "Type ramp", "Fifteen sizes descend evenly; no line clips its descenders (g, y, p).") {
            val base = LabTheme.typography.body
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RampSizes.forEach { size ->
                    Text(
                        "$size sp — Quiet gypsy",
                        style = base.copy(fontSize = size.sp, lineHeight = (size * 1.25f).sp),
                        maxLines = 1,
                    )
                }
            }
        }
        sample(
            "scripts",
            "Scripts & font fallback",
            "Every line shapes correctly with a system fallback font: no tofu boxes, Hebrew and Arabic right-to-left, Arabic letters joined, emoji in colour.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Latin: The quick brown fox — ﬁ ffi ligatures, café, naïve",
                    "Hebrew: שלום עולם, בראשית ברא",
                    "Arabic: مرحبا بالعالم",
                    "CJK: 你好世界 · こんにちは · 안녕하세요",
                    "Devanagari: नमस्ते दुनिया",
                    "Combining: é ä ñ · Z̶alg̶o",
                    "Emoji: 👩🏽‍💻 🏳️‍🌈 🇫🇷 ✅ 🎉",
                    "Mixed: abc אבג 123 مرحبا end",
                ).forEach { Text(it, style = LabTheme.typography.body.copy(fontSize = 16.sp)) }
            }
        }
        sample(
            "spans",
            "Annotated string",
            "Each span shows only its own style; the link is underlined and clickable; superscript sits above the baseline.",
        ) {
            BasicText(
                buildAnnotatedString {
                    append("Plain ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold ") }
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("italic ") }
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("underline ") }
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append("strike ") }
                    withStyle(SpanStyle(color = Color(0xFFE91E63))) { append("colour ") }
                    withStyle(SpanStyle(background = Color(0x6600BCD4))) { append("background ") }
                    append("E=mc")
                    withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 10.sp)) { append("2") }
                    append(" H")
                    withStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 10.sp)) { append("2") }
                    append("O ")
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("mono ") }
                    withLink(LinkAnnotation.Url("https://nucleusframework.dev")) { append("a link") }
                },
                style = LabTheme.typography.body.copy(fontSize = 16.sp, color = LabTheme.colors.text),
            )
        }
        sample(
            "overflow",
            "Overflow & wrapping",
            "Box 1 ends with an ellipsis on line 2; box 2 is one clipped line; box 3 wraps with generous line height and wide letter spacing.",
        ) {
            val text = OVERFLOW_TEXT
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(220.dp).border(1.dp, Color.Gray),
                )
                BasicText(
                    text,
                    style = LabTheme.typography.body.copy(color = LabTheme.colors.text),
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.width(220.dp).border(1.dp, Color.Gray),
                )
                Text(
                    text,
                    style = LabTheme.typography.body.merge(TextStyle(lineHeight = 28.sp, letterSpacing = 2.sp)),
                    modifier = Modifier.width(220.dp).border(1.dp, Color.Gray),
                )
            }
        }
        sample(
            "editing",
            "Editing & selection",
            "The field shows a blinking caret, drag-selects, double-click selects a word, dead keys and IME compose; the paragraph below selects across lines.",
        ) {
            var value by remember { mutableStateOf("Edit me — try ê, ñ, an IME, Ctrl/Cmd+A") }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = LabTheme.typography.body.copy(fontSize = 16.sp, color = LabTheme.colors.text),
                    cursorBrush = SolidColor(LabTheme.colors.accent),
                    modifier = Modifier.fillMaxWidth().border(1.dp, LabTheme.colors.borderStrong).padding(8.dp),
                )
                SelectionContainer {
                    Text(
                        "Selectable paragraph. Drag across these two sentences to select them; the highlight follows the text, not the box.",
                    )
                }
            }
        }
    }

/** Fifteen evenly descending sizes, 40 sp down to 12 sp. */
private val RampSizes = (0 until 15).map { 40 - it * 2 }

private const val OVERFLOW_TEXT =
    "Long text that cannot fit the box it is given, so the layout must decide what to do with the rest of it."
