package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.scrollbarContentSafePadding
import org.jetbrains.jewel.ui.Orientation as JewelOrientation
import org.jetbrains.jewel.ui.component.Divider as JewelDivider
import org.jetbrains.jewel.ui.component.Link as JewelLink
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.ui.component.Tooltip as JewelTooltip

/** The Lab's text. [color] defaults to the style's colour, then to the theme's text colour. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LabTheme.typography.body,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    JewelText(
        text,
        modifier = modifier,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        style = style,
    )
}

/** A hairline between areas. */
@Composable
fun Divider(
    orientation: Orientation = Orientation.Horizontal,
    modifier: Modifier = Modifier,
    color: Color = LabTheme.colors.border,
) {
    val jewelOrientation =
        when (orientation) {
            Orientation.Horizontal -> JewelOrientation.Horizontal
            Orientation.Vertical -> JewelOrientation.Vertical
        }
    JewelDivider(jewelOrientation, modifier, color = color)
}

/** Shows [text] while the pointer rests on [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    JewelTooltip(tooltip = { JewelText(text) }, modifier = modifier, enabled = enabled && text.isNotEmpty()) {
        content()
    }
}

/** An inline text action (open, show details). */
@Composable
fun Link(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    JewelLink(text, onClick, modifier, enabled = enabled, textStyle = LabTheme.typography.body)
}

/** A vertically scrolling column with the IntelliJ scrollbar; the content never runs under it. */
@Composable
fun ScrollableColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    VerticallyScrollableContainer(modifier = modifier, scrollState = scrollState) {
        Column(
            Modifier.fillMaxWidth().padding(contentPadding).padding(end = scrollbarContentSafePadding()),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** A lazy column with the IntelliJ scrollbar. */
@Composable
fun LabLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    VerticallyScrollableContainer(scrollState = state, modifier = modifier) {
        LazyColumn(
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            modifier = Modifier.fillMaxSize().padding(end = scrollbarContentSafePadding()),
            content = content,
        )
    }
}
