package dev.nucleusframework.widget

/** Source of an [Image]: encoded image bytes shipped with the app, or a URL the host downloads. */
public class ImageProvider private constructor(
    /** The encoded image, when built from bytes. */
    public val bytes: ByteArray?,
    /** The MIME type of [bytes] (`image/png`, `image/jpeg`, `image/svg+xml`). */
    public val mimeType: String?,
    /** The image URL, when built from one. */
    public val url: String?,
) {
    /** An image from its encoded [bytes]. */
    public constructor(bytes: ByteArray, mimeType: String = "image/png") : this(bytes, mimeType, null)

    /** An image the host downloads from [url] (`https:`). */
    public constructor(url: String) : this(null, null, url)
}

/**
 * The rendered widget, as composed by the runtime and handed to the platform host. Immutable:
 * the composition is disposed once it is built.
 */
@InternalNucleusWidgetApi
public sealed interface WidgetElement {
    /** Decorations of the element. */
    public val modifier: ResolvedModifier

    /** Children stacked vertically. */
    public data class Column(
        val children: List<WidgetElement>,
        val spacing: Spacing,
        val horizontalAlignment: Alignment.Horizontal,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /** Children laid out horizontally. */
    public data class Row(
        val children: List<WidgetElement>,
        val spacing: Spacing,
        val verticalAlignment: Alignment.Vertical,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /** A text. */
    public data class Text(
        val text: String,
        val style: TextStyle,
        val maxLines: Int,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /** A button. */
    public data class Button(
        val text: String,
        val action: ElementAction,
        val enabled: Boolean,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /** An image. */
    public class Image(
        public val provider: ImageProvider,
        public val contentDescription: String?,
        public val size: ImageSize,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /** Empty space, usually with [ResolvedModifier.fillMaxHeight] to push siblings apart. */
    public data class Spacer(
        override val modifier: ResolvedModifier,
    ) : WidgetElement
}

/** What an element does when clicked, once its [Action] is registered for this render. */
@InternalNucleusWidgetApi
public sealed interface ElementAction {
    /** Runs app code: the host sends [key] back through [WidgetEngine.invokeAction]. */
    public data class Execute(
        val key: String,
    ) : ElementAction

    /** Opens [url]; handled by the host alone. */
    public data class OpenUrl(
        val url: String,
    ) : ElementAction
}

/** A [WidgetModifier] chain flattened, its click action registered. */
@InternalNucleusWidgetApi
public data class ResolvedModifier(
    val fillMaxWidth: Boolean = false,
    val fillMaxHeight: Boolean = false,
    val weight: Int? = null,
    val background: WidgetBackground? = null,
    val onClick: ElementAction? = null,
) {
    internal companion object {
        val None = ResolvedModifier()
    }
}
