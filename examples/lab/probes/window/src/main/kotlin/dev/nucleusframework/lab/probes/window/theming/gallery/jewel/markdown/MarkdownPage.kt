package dev.nucleusframework.lab.probes.window.theming.gallery.jewel.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import org.jetbrains.jewel.foundation.modifier.trackActivation
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.MarkdownMode
import org.jetbrains.jewel.markdown.WithMarkdownMode
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

/** Editor on the left, live GitHub-flavoured preview on the right. */
@Composable
internal fun MarkdownPage() {
    Row(
        Modifier.trackActivation().fillMaxSize().background(JewelTheme.globalColors.panelBackground).semantics {
            isTraversalGroup = true
        },
    ) {
        WithMarkdownMode(MarkdownMode.EditorPreview(scrollingSynchronizer = null)) {
            val editorState = rememberTextFieldState(JewelReadme)
            MarkdownEditor(state = editorState, modifier = Modifier.fillMaxHeight().weight(1f))

            Divider(Orientation.Vertical, Modifier.fillMaxHeight())

            MarkdownPreview(modifier = Modifier.fillMaxHeight().weight(1f), rawMarkdown = editorState.text)
        }
    }
}
