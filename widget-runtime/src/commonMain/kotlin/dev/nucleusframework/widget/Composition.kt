@file:OptIn(InternalNucleusWidgetApi::class)

package dev.nucleusframework.widget

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.coroutines.EmptyCoroutineContext

/** Mutable node built by the applier; turned into an immutable [WidgetElement] once composed. */
internal sealed class WidgetNode {
    var modifier: ResolvedModifier = ResolvedModifier.None

    abstract fun toElement(): WidgetElement?

    abstract class Container : WidgetNode() {
        val children = mutableListOf<WidgetNode>()

        protected fun childElements(): List<WidgetElement> = children.mapNotNull { it.toElement() }
    }

    class Root : Container() {
        override fun toElement(): WidgetElement =
            WidgetElement.Column(childElements(), Spacing.Default, Alignment.Horizontal.Start, modifier)
    }

    class Column : Container() {
        var spacing = Spacing.Default
        var horizontalAlignment = Alignment.Horizontal.Start

        override fun toElement(): WidgetElement =
            WidgetElement.Column(childElements(), spacing, horizontalAlignment, modifier)
    }

    class Row : Container() {
        var spacing = Spacing.Default
        var verticalAlignment = Alignment.Vertical.Top

        override fun toElement(): WidgetElement =
            WidgetElement.Row(childElements(), spacing, verticalAlignment, modifier)
    }

    class Text : WidgetNode() {
        var text = ""
        var style = TextStyle()
        var maxLines = Int.MAX_VALUE

        override fun toElement(): WidgetElement = WidgetElement.Text(text, style, maxLines, modifier)
    }

    class Button : WidgetNode() {
        var text = ""
        var action: ElementAction? = null
        var enabled = true

        override fun toElement(): WidgetElement? = action?.let { WidgetElement.Button(text, it, enabled, modifier) }
    }

    class Image : WidgetNode() {
        var provider: ImageProvider? = null
        var contentDescription: String? = null
        var size = ImageSize.Auto

        override fun toElement(): WidgetElement? =
            provider?.let { WidgetElement.Image(it, contentDescription, size, modifier) }
    }

    class Spacer : WidgetNode() {
        override fun toElement(): WidgetElement = WidgetElement.Spacer(modifier)
    }
}

internal class WidgetApplier(
    root: WidgetNode.Root,
) : AbstractApplier<WidgetNode>(root) {
    private val container get() = current as WidgetNode.Container

    override fun insertTopDown(
        index: Int,
        instance: WidgetNode,
    ) = Unit

    override fun insertBottomUp(
        index: Int,
        instance: WidgetNode,
    ) {
        container.children.add(index, instance)
    }

    override fun remove(
        index: Int,
        count: Int,
    ) {
        container.children.subList(index, index + count).clear()
    }

    override fun move(
        from: Int,
        to: Int,
        count: Int,
    ) {
        container.children.move(from, to, count)
    }

    override fun onClear() {
        (root as WidgetNode.Container).children.clear()
    }
}

/**
 * Assigns each [Action] of one render a key the host sends back when it is invoked. Keys are
 * positional (`a0`, `a1`, …): the same content composed from the same state yields the same keys,
 * so a key from a card rendered before the process restarted still resolves after a re-render.
 */
internal class ActionRegistry {
    val actions = mutableMapOf<String, Action>()

    fun register(action: Action): ElementAction =
        when (action) {
            is OpenUrlAction -> ElementAction.OpenUrl(action.url)
            else -> {
                val key = "a${actions.size}"
                actions[key] = action
                ElementAction.Execute(key)
            }
        }

    fun resolve(modifier: WidgetModifier): ResolvedModifier {
        var resolved = ResolvedModifier.None
        for (element in modifier.elements()) {
            resolved =
                when (element) {
                    WidgetModifier.FillMaxWidth -> resolved.copy(fillMaxWidth = true)
                    WidgetModifier.FillMaxHeight -> resolved.copy(fillMaxHeight = true)
                    is WidgetModifier.Weight -> resolved.copy(weight = element.weight)
                    is WidgetModifier.Background -> resolved.copy(background = element.background)
                    is WidgetModifier.Clickable -> resolved.copy(onClick = register(element.action))
                }
        }
        return resolved
    }
}

internal val LocalActionRegistry =
    staticCompositionLocalOf<ActionRegistry> { error("Widget content composed outside a widget render") }

/** Composes [content] once, synchronously, and returns the element tree and the actions it registered. */
internal fun composeOnce(
    locals: @Composable (@Composable () -> Unit) -> Unit,
    content: @Composable () -> Unit,
): Pair<WidgetElement, Map<String, Action>> {
    val root = WidgetNode.Root()
    val registry = ActionRegistry()
    // A Recomposer that never runs: the initial composition happens synchronously in setContent,
    // and nothing is recomposed afterwards (a widget is a snapshot, not a live UI).
    val recomposer = Recomposer(EmptyCoroutineContext)
    val composition = Composition(WidgetApplier(root), recomposer)
    try {
        composition.setContent {
            CompositionLocalProvider(LocalActionRegistry provides registry) { locals(content) }
        }
        return root.toElement() to registry.actions.toMap()
    } finally {
        composition.dispose()
        recomposer.cancel()
    }
}
