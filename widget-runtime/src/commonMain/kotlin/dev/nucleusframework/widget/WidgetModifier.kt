package dev.nucleusframework.widget

/**
 * An ordered set of decorations for a widget element, chained like Compose's `Modifier`:
 * `WidgetModifier.fillMaxWidth().background(WidgetBackground.Emphasis)`.
 *
 * Only what every widget host can render is offered: there is no padding or arbitrary size,
 * since hosts lay widgets out on their own spacing grid.
 */
@ExperimentalNucleusWidgetApi
public sealed interface WidgetModifier {
    /** Appends [other] after this modifier. */
    public infix fun then(other: WidgetModifier): WidgetModifier =
        if (other === WidgetModifier) this else Combined(this, other)

    /** The empty modifier, start of every chain. */
    public companion object : WidgetModifier {
        override fun then(other: WidgetModifier): WidgetModifier = other
    }

    /** One decoration of the chain. */
    @InternalNucleusWidgetApi
    public sealed interface Element : WidgetModifier

    /** Takes the whole width offered by the parent [Row]. */
    @InternalNucleusWidgetApi
    public data object FillMaxWidth : Element

    /** Takes the whole height offered by the parent [Column] (pushes the following children down). */
    @InternalNucleusWidgetApi
    public data object FillMaxHeight : Element

    /** Shares the parent [Row]'s width with its siblings in proportion to [weight]. */
    @InternalNucleusWidgetApi
    public data class Weight(
        val weight: Int,
    ) : Element

    /** A semantic background. */
    @InternalNucleusWidgetApi
    public data class Background(
        val background: WidgetBackground,
    ) : Element

    /** Makes the element clickable. */
    @InternalNucleusWidgetApi
    public class Clickable(
        public val action: Action,
    ) : Element

    private class Combined(
        val outer: WidgetModifier,
        val inner: WidgetModifier,
    ) : WidgetModifier

    /** Flattens the chain, first element first. */
    @InternalNucleusWidgetApi
    public fun elements(): List<Element> =
        when (this) {
            WidgetModifier.Companion -> emptyList()
            is Combined -> outer.elements() + inner.elements()
            is Element -> listOf(this)
        }
}

/** See [WidgetModifier.FillMaxWidth]. */
@OptIn(InternalNucleusWidgetApi::class)
public fun WidgetModifier.fillMaxWidth(): WidgetModifier = then(WidgetModifier.FillMaxWidth)

/** See [WidgetModifier.FillMaxHeight]. */
@OptIn(InternalNucleusWidgetApi::class)
public fun WidgetModifier.fillMaxHeight(): WidgetModifier = then(WidgetModifier.FillMaxHeight)

/** Takes the whole width and height offered by the parent. */
@ExperimentalNucleusWidgetApi
public fun WidgetModifier.fillMaxSize(): WidgetModifier = fillMaxWidth().fillMaxHeight()

/** See [WidgetModifier.Weight]; [weight] must be positive. */
@OptIn(InternalNucleusWidgetApi::class)
public fun WidgetModifier.weight(weight: Int): WidgetModifier {
    require(weight > 0) { "weight must be positive, was $weight" }
    return then(WidgetModifier.Weight(weight))
}

/** See [WidgetModifier.Background]. */
@OptIn(InternalNucleusWidgetApi::class)
public fun WidgetModifier.background(background: WidgetBackground): WidgetModifier =
    then(WidgetModifier.Background(background))

/** Runs [action] when the element is clicked. */
@OptIn(InternalNucleusWidgetApi::class)
public fun WidgetModifier.clickable(action: Action): WidgetModifier = then(WidgetModifier.Clickable(action))

/** Runs [onClick] when the element is clicked; the widget is rendered again afterwards. */
@ExperimentalNucleusWidgetApi
public fun WidgetModifier.clickable(onClick: () -> Unit): WidgetModifier = clickable(LambdaAction(onClick))
