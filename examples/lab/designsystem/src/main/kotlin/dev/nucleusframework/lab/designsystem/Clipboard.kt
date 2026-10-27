package dev.nucleusframework.lab.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** A `copy(text)` action for effects that put text on the clipboard (reports, TSV, links). */
@Suppress("DEPRECATION")
@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return { text -> clipboard.setText(AnnotatedString(text)) }
}
