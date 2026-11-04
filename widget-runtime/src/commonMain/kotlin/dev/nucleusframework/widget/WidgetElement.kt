package dev.nucleusframework.widget

/**
 * Source of an [Image]. Prefer [appResource] for anything but small generated images: encoded
 * [bytes] travel inside every card the host receives, while a resource is read by the host from the
 * installed app.
 */
public class ImageProvider private constructor(
    /** The encoded image, when built from bytes. */
    public val bytes: ByteArray?,
    /** The MIME type of [bytes] (`image/png`, `image/jpeg`, `image/svg+xml`). */
    public val mimeType: String?,
    /** The image URL, when built from one. */
    public val url: String?,
    /** The path of an app resource (under `appResourcesRootDir`), when built from one. */
    public val appResource: String?,
) {
    /** An image from its encoded [bytes]; keep it small, it is embedded in the card. */
    public constructor(bytes: ByteArray, mimeType: String = "image/png") : this(bytes, mimeType, null, null)

    /** An image the host downloads from [url] (`https:`). */
    public constructor(url: String) : this(null, null, url, null)

    /** Factories. */
    public companion object {
        /**
         * An image shipped as an app resource: [path] is relative to the app's resources directory
         * (the Gradle plugin's `appResourcesRootDir`, e.g. `"widgets/logo.png"`). The host loads it from
         * the installed package, so it costs nothing per update.
         */
        public fun appResource(path: String): ImageProvider =
            ImageProvider(null, null, null, path.replace('\\', '/').trimStart('/'))
    }
}

/** One option of a [ChoiceField]: [title] is shown, [value] is stored in the widget state. */
public data class Choice(
    val title: String,
    val value: String,
)

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

    /** A text field; [id] is the state key its value is stored under. */
    public data class TextInput(
        val id: String,
        val value: String?,
        val label: String?,
        val placeholder: String?,
        val isMultiline: Boolean,
        val maxLength: Int?,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /** An on/off switch; [id] is the state key its value is stored under. */
    public data class Toggle(
        val id: String,
        val title: String,
        val value: Boolean,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /** A choice among [choices]; [id] is the state key the chosen value is stored under. */
    public data class ChoiceInput(
        val id: String,
        val value: String?,
        val choices: List<Choice>,
        val label: String?,
        val isExpanded: Boolean,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /** A number field; [id] is the state key its value is stored under. */
    public data class NumberInput(
        val id: String,
        val value: Double?,
        val label: String?,
        val min: Double?,
        val max: Double?,
        override val modifier: ResolvedModifier,
    ) : WidgetElement

    /**
     * Web content shown instead of the widget's other elements where the host supports it; those
     * elements are the fallback elsewhere.
     */
    public data class WebContent(
        val url: String,
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
