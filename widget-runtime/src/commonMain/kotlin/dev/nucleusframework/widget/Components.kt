package dev.nucleusframework.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode

/** Stacks [content] vertically, [spacing] apart. */
@Composable
public fun Column(
    modifier: WidgetModifier = WidgetModifier,
    spacing: Spacing = Spacing.Default,
    horizontalAlignment: Alignment.Horizontal = Alignment.Horizontal.Start,
    content: @Composable () -> Unit,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    ComposeNode<WidgetNode.Column, WidgetApplier>(
        factory = WidgetNode::Column,
        update = {
            set(resolved) { this.modifier = it }
            set(spacing) { this.spacing = it }
            set(horizontalAlignment) { this.horizontalAlignment = it }
        },
        content = content,
    )
}

/** Lays [content] out horizontally, [spacing] apart. Children share the width as [WidgetModifier.weight] says. */
@Composable
public fun Row(
    modifier: WidgetModifier = WidgetModifier,
    spacing: Spacing = Spacing.Default,
    verticalAlignment: Alignment.Vertical = Alignment.Vertical.Top,
    content: @Composable () -> Unit,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    ComposeNode<WidgetNode.Row, WidgetApplier>(
        factory = WidgetNode::Row,
        update = {
            set(resolved) { this.modifier = it }
            set(spacing) { this.spacing = it }
            set(verticalAlignment) { this.verticalAlignment = it }
        },
        content = content,
    )
}

/** Displays [text], wrapping to at most [maxLines] lines. */
@Composable
public fun Text(
    text: String,
    modifier: WidgetModifier = WidgetModifier,
    style: TextStyle = TextStyle(),
    maxLines: Int = Int.MAX_VALUE,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    ComposeNode<WidgetNode.Text, WidgetApplier>(
        factory = WidgetNode::Text,
        update = {
            set(resolved) { this.modifier = it }
            set(text) { this.text = it }
            set(style) { this.style = it }
            set(maxLines) { this.maxLines = it }
        },
    )
}

/** A button labelled [text] that runs [onClick]. */
@Composable
public fun Button(
    text: String,
    onClick: Action,
    modifier: WidgetModifier = WidgetModifier,
    enabled: Boolean = true,
) {
    val registry = LocalActionRegistry.current
    val resolved = registry.resolve(modifier)
    val action = registry.register(onClick)
    ComposeNode<WidgetNode.Button, WidgetApplier>(
        factory = WidgetNode::Button,
        update = {
            set(resolved) { this.modifier = it }
            set(text) { this.text = it }
            set(action) { this.action = it }
            set(enabled) { this.enabled = it }
        },
    )
}

/** A button labelled [text] that runs [onClick]; the widget is rendered again afterwards. */
@Composable
public fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: WidgetModifier = WidgetModifier,
    enabled: Boolean = true,
): Unit = Button(text, LambdaAction(onClick), modifier, enabled)

/** Displays the image of [provider]. */
@Composable
public fun Image(
    provider: ImageProvider,
    contentDescription: String?,
    modifier: WidgetModifier = WidgetModifier,
    size: ImageSize = ImageSize.Auto,
) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    ComposeNode<WidgetNode.Image, WidgetApplier>(
        factory = WidgetNode::Image,
        update = {
            set(resolved) { this.modifier = it }
            set(provider) { this.provider = it }
            set(contentDescription) { this.contentDescription = it }
            set(size) { this.size = it }
        },
    )
}

/** Empty space; with [fillMaxHeight] it pushes the following children to the bottom. */
@Composable
public fun Spacer(modifier: WidgetModifier = WidgetModifier) {
    val resolved = LocalActionRegistry.current.resolve(modifier)
    ComposeNode<WidgetNode.Spacer, WidgetApplier>(
        factory = WidgetNode::Spacer,
        update = { set(resolved) { this.modifier = it } },
    )
}
