@file:Suppress("LongParameterList")

package dev.nucleusframework.notification.windows

// =============================================================================
// Toast content — root element
// =============================================================================

/**
 * Root data model for a Windows toast notification.
 *
 * Maps the full `ToastContent` XML schema.
 *
 * @param visual Required visual content (text, images, groups, progress bars).
 * @param actions Optional interactive elements (buttons, inputs, context menu).
 * @param audio Optional sound configuration.
 * @param header Optional Action Center header for grouping.
 * @param launch App-defined launch arguments when the toast body is clicked.
 * @param activationType How the app is activated on click.
 * @param scenario Pre-defined display/audio behavior.
 * @param duration How long the toast stays on screen before moving to the Action Center.
 * @param displayTimestamp Custom timestamp override (ISO 8601 string).
 */
public data class ToastContent(
    val visual: ToastVisual,
    val actions: ToastActions? = null,
    val audio: ToastAudio? = null,
    val header: ToastHeader? = null,
    val launch: String = "",
    val activationType: ActivationType = ActivationType.FOREGROUND,
    val scenario: ToastScenario = ToastScenario.DEFAULT,
    val duration: ToastDuration = ToastDuration.DEFAULT,
    val displayTimestamp: String? = null,
)

// =============================================================================
// Visual
// =============================================================================

/**
 * Visual content of a toast notification.
 *
 * @param binding The generic toast binding containing text, images, groups, etc.
 */
public data class ToastVisual(
    val binding: ToastBindingGeneric,
)

/**
 * Generic toast binding — the main container for visual elements.
 *
 * @param children Ordered list of visual elements (text, images, groups, progress bars).
 * @param appLogoOverride Optional image to replace the app logo.
 * @param heroImage Optional large hero image displayed at the top.
 * @param attribution Optional attribution text at the bottom.
 */
public data class ToastBindingGeneric(
    val children: List<ToastVisualChild> = emptyList(),
    val appLogoOverride: ToastGenericAppLogo? = null,
    val heroImage: ToastGenericHeroImage? = null,
    val attribution: ToastGenericAttributionText? = null,
)

/** Marker interface for elements that can appear in a binding's children list. */
public sealed interface ToastVisualChild

// -- Text --

/**
 * An adaptive text element within the toast.
 *
 * Up to 3 text elements can appear at the top level of a binding:
 * - 1st: title (bold)
 * - 2nd: body line 1
 * - 3rd: body line 2
 *
 * Inside groups, unlimited text with full styling.
 *
 * @param text The text content. Supports data binding with `{key}` syntax.
 * @param hintStyle Text style (only effective inside groups).
 * @param hintWrap Whether to wrap text (default false for top-level, true in groups).
 * @param hintMaxLines Maximum number of lines.
 * @param hintMinLines Minimum number of lines (only in groups).
 * @param hintAlign Text alignment (only in groups).
 * @param language BCP-47 locale override.
 */
public data class AdaptiveText(
    val text: String,
    val hintStyle: AdaptiveTextStyle = AdaptiveTextStyle.DEFAULT,
    val hintWrap: Boolean? = null,
    val hintMaxLines: Int? = null,
    val hintMinLines: Int? = null,
    val hintAlign: AdaptiveTextAlign = AdaptiveTextAlign.DEFAULT,
    val language: String? = null,
) : ToastVisualChild,
    AdaptiveSubgroupChild

// -- Images --

/**
 * An inline image within the toast body.
 *
 * @param source Image URI (ms-appx:///, ms-appdata:///, http/https, file:///).
 * @param hintCrop How to crop the image.
 * @param hintRemoveMargin Remove default 8px margin (only in groups).
 * @param hintAlign Image alignment (only in groups).
 * @param alternateText Accessibility description.
 * @param addImageQuery Append system query string for scale/contrast/language.
 */
public data class AdaptiveImage(
    val source: String,
    val hintCrop: AdaptiveImageCrop = AdaptiveImageCrop.DEFAULT,
    val hintRemoveMargin: Boolean? = null,
    val hintAlign: AdaptiveImageAlign = AdaptiveImageAlign.DEFAULT,
    val alternateText: String? = null,
    val addImageQuery: Boolean? = null,
) : ToastVisualChild,
    AdaptiveSubgroupChild

/** App logo override image (displayed left of the text). */
public data class ToastGenericAppLogo(
    val source: String,
    val hintCrop: AdaptiveImageCrop = AdaptiveImageCrop.DEFAULT,
    val alternateText: String? = null,
    val addImageQuery: Boolean? = null,
)

/** Hero image displayed at the top of the toast. */
public data class ToastGenericHeroImage(
    val source: String,
    val alternateText: String? = null,
    val addImageQuery: Boolean? = null,
)

/** Attribution text displayed at the bottom of the toast. */
public data class ToastGenericAttributionText(
    val text: String,
    val language: String? = null,
)

// =============================================================================
// Adaptive layout — groups & subgroups
// =============================================================================

/**
 * A group of subgroups forming a multi-column layout.
 *
 * Requires Anniversary Update (build 15063+).
 *
 * @param subgroups The column definitions.
 */
public data class AdaptiveGroup(
    val subgroups: List<AdaptiveSubgroup>,
) : ToastVisualChild

/**
 * A single column within a group.
 *
 * @param children Text and image elements within this column.
 * @param hintWeight Relative column width (integer weight).
 * @param hintTextStacking Vertical alignment of content.
 */
public data class AdaptiveSubgroup(
    val children: List<AdaptiveSubgroupChild> = emptyList(),
    val hintWeight: Int? = null,
    val hintTextStacking: AdaptiveSubgroupTextStacking = AdaptiveSubgroupTextStacking.DEFAULT,
)

/** Marker interface for elements that can appear in a subgroup. */
public sealed interface AdaptiveSubgroupChild

// =============================================================================
// Progress bar
// =============================================================================

/**
 * A progress bar element within the toast.
 *
 * Requires Creators Update (build 15063+). Desktop only.
 *
 * For data binding (live updates via [WindowsNotificationCenter.update]),
 * use the `{key}` syntax in string fields or set [valueBind] for the progress value.
 * Example: `status = "{progressStatus}"`, `valueBind = "progressValue"`.
 *
 * @param title Optional title above the progress bar. Supports `{key}` binding.
 * @param value Progress value: 0.0–1.0 for determinate, or null for indeterminate.
 * @param valueBind Data binding key for the progress value (overrides [value]).
 * @param valueStringOverride Custom string instead of default percentage. Supports `{key}` binding.
 * @param status Required status text. Supports `{key}` binding.
 */
public data class AdaptiveProgressBar(
    val title: String? = null,
    val value: Double? = null,
    val valueBind: String? = null,
    val valueStringOverride: String? = null,
    val status: String,
) : ToastVisualChild

// =============================================================================
// Actions — buttons, inputs, context menu
// =============================================================================

/**
 * Container for interactive elements on the toast.
 *
 * @param inputs Input elements (text boxes, selection boxes). Max 5 total.
 * @param buttons Action buttons. Max 5 total (shared with context menu items).
 * @param contextMenuItems Right-click context menu items.
 */
public data class ToastActions(
    val inputs: List<ToastInput> = emptyList(),
    val buttons: List<ToastButton> = emptyList(),
    val contextMenuItems: List<ToastContextMenuItem> = emptyList(),
)

// -- Inputs --

/** Marker interface for toast input elements. */
public sealed interface ToastInput {
    public val id: String
}

/**
 * A text input box on the toast.
 *
 * @param id Unique identifier (key for retrieving user input).
 * @param title Label displayed above the text box.
 * @param placeholderContent Hint text when empty.
 * @param defaultInput Initial value.
 */
public data class ToastTextBox(
    override val id: String,
    val title: String? = null,
    val placeholderContent: String? = null,
    val defaultInput: String? = null,
) : ToastInput

/**
 * A dropdown selection box on the toast.
 *
 * @param id Unique identifier (key for retrieving selection).
 * @param title Label displayed above the selection box.
 * @param defaultSelectionBoxItemId ID of the pre-selected item.
 * @param items Available selection items.
 */
public data class ToastSelectionBox(
    override val id: String,
    val title: String? = null,
    val defaultSelectionBoxItemId: String? = null,
    val items: List<ToastSelectionBoxItem> = emptyList(),
) : ToastInput

/**
 * A single item within a selection box.
 *
 * @param id Unique identifier for this item.
 * @param content Display text.
 */
public data class ToastSelectionBoxItem(
    val id: String,
    val content: String,
)

// -- Buttons --

/**
 * An action button on the toast.
 *
 * @param content Button text.
 * @param arguments App-defined arguments passed on activation.
 * @param activationType How the app is activated when this button is pressed.
 * @param imageUri Optional icon for the button (square image).
 * @param inputId If set, the button is placed next to the specified input element.
 * @param afterActivationBehavior What happens to the toast after activation.
 * @param tooltipText Tooltip shown on hover.
 */
public data class ToastButton(
    val content: String,
    val arguments: String,
    val activationType: ActivationType = ActivationType.FOREGROUND,
    val imageUri: String? = null,
    val inputId: String? = null,
    val afterActivationBehavior: AfterActivationBehavior = AfterActivationBehavior.DEFAULT,
    val tooltipText: String? = null,
)

/**
 * System-handled snooze button. Automatically picks a snooze interval.
 *
 * @param customContent Override the default "Snooze" label.
 * @param selectionBoxId ID of a selection box containing snooze intervals.
 */
public data class ToastButtonSnooze(
    val customContent: String? = null,
    val selectionBoxId: String? = null,
)

/**
 * System-handled dismiss button.
 *
 * @param customContent Override the default "Dismiss" label.
 */
public data class ToastButtonDismiss(
    val customContent: String? = null,
)

// -- Context menu --

/**
 * A right-click context menu item on the toast.
 *
 * @param content Menu item text.
 * @param arguments App-defined arguments passed on activation.
 * @param activationType How the app is activated.
 */
public data class ToastContextMenuItem(
    val content: String,
    val arguments: String,
    val activationType: ActivationType = ActivationType.FOREGROUND,
)

// =============================================================================
// Audio
// =============================================================================

/**
 * Audio configuration for a toast notification.
 *
 * @param source Audio source. Use [ToastAudioSource] constants or a custom `ms-appx:///` URI.
 * @param loop Whether to loop the sound while the toast is visible.
 * @param silent Whether to mute the sound entirely.
 */
public data class ToastAudio(
    val source: ToastAudioSource? = null,
    val customSource: String? = null,
    val loop: Boolean = false,
    val silent: Boolean = false,
)

// =============================================================================
// Header
// =============================================================================

/**
 * Groups multiple toasts under a single header in Action Center.
 *
 * Requires Creators Update (build 15063+). Desktop only.
 *
 * @param id Unique identifier for this header group.
 * @param title Display text for the header.
 * @param arguments App-defined arguments when the header is clicked.
 * @param activationType How the app is activated when the header is clicked.
 */
public data class ToastHeader(
    val id: String,
    val title: String,
    val arguments: String,
    val activationType: ActivationType = ActivationType.FOREGROUND,
)

// =============================================================================
// Notification data — for progress bar updates
// =============================================================================

/**
 * Data for updating a toast's data-bound fields (progress bars, etc.)
 * without re-sending the entire notification.
 *
 * @param sequenceNumber Monotonically increasing number to avoid race conditions.
 * @param values Key-value pairs mapping `{key}` bindings to their values.
 */
public data class ToastNotificationData(
    val sequenceNumber: Int = 0,
    val values: Map<String, String> = emptyMap(),
)

// =============================================================================
// Callbacks / responses
// =============================================================================

/**
 * Activation arguments received when the user interacts with the toast.
 *
 * @param arguments The app-defined launch string.
 * @param userInputs Map of input ID → user-provided value.
 */
public data class ToastActivatedEventArgs(
    val arguments: String,
    val userInputs: Map<String, String> = emptyMap(),
)

/**
 * Event when a toast is dismissed.
 *
 * @param reason Why the toast was dismissed.
 */
public data class ToastDismissedEventArgs(
    val reason: DismissalReason,
)

/**
 * Event when a toast fails to display.
 *
 * @param errorCode The HRESULT error code.
 */
public data class ToastFailedEventArgs(
    val errorCode: Int,
)
