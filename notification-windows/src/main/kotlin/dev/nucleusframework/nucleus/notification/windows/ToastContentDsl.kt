package dev.nucleusframework.nucleus.notification.windows

/**
 * Kotlin DSL for building toast notification content.
 *
 * Example:
 * ```kotlin
 * val toast = toast {
 *     launch = "action=viewMessage&messageId=123"
 *     scenario = ToastScenario.REMINDER
 *
 *     visual {
 *         text("Meeting Reminder")
 *         text("Team standup in 5 minutes")
 *         image("https://example.com/hero.png", placement = ImagePlacement.HERO)
 *         appLogo("https://example.com/logo.png", crop = AdaptiveImageCrop.CIRCLE)
 *         attribution("via Calendar")
 *
 *         progressBar(
 *             title = "Downloading...",
 *             value = 0.5,
 *             status = "50% complete",
 *         )
 *
 *         group {
 *             subgroup(weight = 1) {
 *                 text("Column 1", style = AdaptiveTextStyle.CAPTION)
 *             }
 *             subgroup(weight = 2) {
 *                 text("Column 2", style = AdaptiveTextStyle.BODY)
 *             }
 *         }
 *     }
 *
 *     actions {
 *         textBox("replyBox", title = "Reply", placeholder = "Type a message...")
 *         selectionBox("snoozeTime", title = "Snooze for") {
 *             item("5", "5 minutes")
 *             item("15", "15 minutes")
 *             item("60", "1 hour")
 *         }
 *         button("Reply", arguments = "action=reply", inputId = "replyBox")
 *         button("Dismiss", arguments = "action=dismiss", activationType = ActivationType.BACKGROUND)
 *         contextMenuItem("Open settings", arguments = "action=settings")
 *     }
 *
 *     audio(ToastAudioSource.REMINDER, loop = false)
 *
 *     header(id = "meetings", title = "Meetings", arguments = "action=openMeetings")
 * }
 * ```
 */
fun toast(block: ToastContentBuilder.() -> Unit): ToastContent = ToastContentBuilder().apply(block).build()

@DslMarker
annotation class ToastDsl

@ToastDsl
class ToastContentBuilder {
    var launch: String = ""
    var activationType: ActivationType = ActivationType.FOREGROUND
    var scenario: ToastScenario = ToastScenario.DEFAULT
    var displayTimestamp: String? = null

    private var visual: ToastVisual? = null
    private var actions: ToastActions? = null
    private var audio: ToastAudio? = null
    private var header: ToastHeader? = null

    fun visual(block: ToastVisualBuilder.() -> Unit) {
        visual = ToastVisualBuilder().apply(block).build()
    }

    fun actions(block: ToastActionsBuilder.() -> Unit) {
        actions = ToastActionsBuilder().apply(block).build()
    }

    fun audio(
        source: ToastAudioSource? = null,
        customSource: String? = null,
        loop: Boolean = false,
        silent: Boolean = false,
    ) {
        audio = ToastAudio(source = source, customSource = customSource, loop = loop, silent = silent)
    }

    fun silentAudio() {
        audio = ToastAudio(silent = true)
    }

    fun header(
        id: String,
        title: String,
        arguments: String,
        activationType: ActivationType = ActivationType.FOREGROUND,
    ) {
        header = ToastHeader(id = id, title = title, arguments = arguments, activationType = activationType)
    }

    internal fun build(): ToastContent {
        requireNotNull(visual) { "Toast must have visual content" }
        return ToastContent(
            visual = visual!!,
            actions = actions,
            audio = audio,
            header = header,
            launch = launch,
            activationType = activationType,
            scenario = scenario,
            displayTimestamp = displayTimestamp,
        )
    }
}

@ToastDsl
class ToastVisualBuilder {
    private val children = mutableListOf<ToastVisualChild>()
    private var appLogoOverride: ToastGenericAppLogo? = null
    private var heroImage: ToastGenericHeroImage? = null
    private var attribution: ToastGenericAttributionText? = null

    fun text(
        content: String,
        style: AdaptiveTextStyle = AdaptiveTextStyle.DEFAULT,
        wrap: Boolean? = null,
        maxLines: Int? = null,
        minLines: Int? = null,
        align: AdaptiveTextAlign = AdaptiveTextAlign.DEFAULT,
        language: String? = null,
    ) {
        children.add(
            AdaptiveText(
                text = content,
                hintStyle = style,
                hintWrap = wrap,
                hintMaxLines = maxLines,
                hintMinLines = minLines,
                hintAlign = align,
                language = language,
            ),
        )
    }

    fun image(
        source: String,
        crop: AdaptiveImageCrop = AdaptiveImageCrop.DEFAULT,
        alt: String? = null,
        addImageQuery: Boolean? = null,
    ) {
        children.add(
            AdaptiveImage(
                source = source,
                hintCrop = crop,
                alternateText = alt,
                addImageQuery = addImageQuery,
            ),
        )
    }

    fun appLogo(
        source: String,
        crop: AdaptiveImageCrop = AdaptiveImageCrop.DEFAULT,
        alt: String? = null,
        addImageQuery: Boolean? = null,
    ) {
        appLogoOverride =
            ToastGenericAppLogo(
                source = source,
                hintCrop = crop,
                alternateText = alt,
                addImageQuery = addImageQuery,
            )
    }

    fun heroImage(
        source: String,
        alt: String? = null,
        addImageQuery: Boolean? = null,
    ) {
        heroImage =
            ToastGenericHeroImage(
                source = source,
                alternateText = alt,
                addImageQuery = addImageQuery,
            )
    }

    fun attribution(
        text: String,
        language: String? = null,
    ) {
        attribution = ToastGenericAttributionText(text = text, language = language)
    }

    fun progressBar(
        status: String,
        title: String? = null,
        value: Double? = null,
        valueStringOverride: String? = null,
    ) {
        children.add(
            AdaptiveProgressBar(
                title = title,
                value = value,
                valueStringOverride = valueStringOverride,
                status = status,
            ),
        )
    }

    fun group(block: AdaptiveGroupBuilder.() -> Unit) {
        children.add(AdaptiveGroupBuilder().apply(block).build())
    }

    internal fun build(): ToastVisual =
        ToastVisual(
            binding =
                ToastBindingGeneric(
                    children = children.toList(),
                    appLogoOverride = appLogoOverride,
                    heroImage = heroImage,
                    attribution = attribution,
                ),
        )
}

@ToastDsl
class AdaptiveGroupBuilder {
    private val subgroups = mutableListOf<AdaptiveSubgroup>()

    fun subgroup(
        weight: Int? = null,
        textStacking: AdaptiveSubgroupTextStacking = AdaptiveSubgroupTextStacking.DEFAULT,
        block: AdaptiveSubgroupBuilder.() -> Unit,
    ) {
        subgroups.add(
            AdaptiveSubgroupBuilder(weight, textStacking).apply(block).build(),
        )
    }

    internal fun build(): AdaptiveGroup = AdaptiveGroup(subgroups = subgroups.toList())
}

@ToastDsl
class AdaptiveSubgroupBuilder(
    private val weight: Int?,
    private val textStacking: AdaptiveSubgroupTextStacking,
) {
    private val children = mutableListOf<AdaptiveSubgroupChild>()

    fun text(
        content: String,
        style: AdaptiveTextStyle = AdaptiveTextStyle.DEFAULT,
        wrap: Boolean? = null,
        maxLines: Int? = null,
        minLines: Int? = null,
        align: AdaptiveTextAlign = AdaptiveTextAlign.DEFAULT,
        language: String? = null,
    ) {
        children.add(
            AdaptiveText(
                text = content,
                hintStyle = style,
                hintWrap = wrap,
                hintMaxLines = maxLines,
                hintMinLines = minLines,
                hintAlign = align,
                language = language,
            ),
        )
    }

    fun image(
        source: String,
        crop: AdaptiveImageCrop = AdaptiveImageCrop.DEFAULT,
        removeMargin: Boolean? = null,
        align: AdaptiveImageAlign = AdaptiveImageAlign.DEFAULT,
        alt: String? = null,
        addImageQuery: Boolean? = null,
    ) {
        children.add(
            AdaptiveImage(
                source = source,
                hintCrop = crop,
                hintRemoveMargin = removeMargin,
                hintAlign = align,
                alternateText = alt,
                addImageQuery = addImageQuery,
            ),
        )
    }

    internal fun build(): AdaptiveSubgroup =
        AdaptiveSubgroup(
            children = children.toList(),
            hintWeight = weight,
            hintTextStacking = textStacking,
        )
}

@ToastDsl
class ToastActionsBuilder {
    private val inputs = mutableListOf<ToastInput>()
    private val buttons = mutableListOf<ToastButton>()
    private val contextMenuItems = mutableListOf<ToastContextMenuItem>()

    fun textBox(
        id: String,
        title: String? = null,
        placeholder: String? = null,
        defaultInput: String? = null,
    ) {
        inputs.add(
            ToastTextBox(
                id = id,
                title = title,
                placeholderContent = placeholder,
                defaultInput = defaultInput,
            ),
        )
    }

    fun selectionBox(
        id: String,
        title: String? = null,
        defaultSelectionId: String? = null,
        block: SelectionBoxBuilder.() -> Unit,
    ) {
        val items = SelectionBoxBuilder().apply(block).build()
        inputs.add(
            ToastSelectionBox(
                id = id,
                title = title,
                defaultSelectionBoxItemId = defaultSelectionId,
                items = items,
            ),
        )
    }

    fun button(
        content: String,
        arguments: String,
        activationType: ActivationType = ActivationType.FOREGROUND,
        imageUri: String? = null,
        inputId: String? = null,
        afterActivation: AfterActivationBehavior = AfterActivationBehavior.DEFAULT,
        tooltip: String? = null,
    ) {
        buttons.add(
            ToastButton(
                content = content,
                arguments = arguments,
                activationType = activationType,
                imageUri = imageUri,
                inputId = inputId,
                afterActivationBehavior = afterActivation,
                tooltipText = tooltip,
            ),
        )
    }

    fun contextMenuItem(
        content: String,
        arguments: String,
        activationType: ActivationType = ActivationType.FOREGROUND,
    ) {
        contextMenuItems.add(
            ToastContextMenuItem(
                content = content,
                arguments = arguments,
                activationType = activationType,
            ),
        )
    }

    internal fun build(): ToastActions =
        ToastActions(
            inputs = inputs.toList(),
            buttons = buttons.toList(),
            contextMenuItems = contextMenuItems.toList(),
        )
}

@ToastDsl
class SelectionBoxBuilder {
    private val items = mutableListOf<ToastSelectionBoxItem>()

    fun item(
        id: String,
        content: String,
    ) {
        items.add(ToastSelectionBoxItem(id = id, content = content))
    }

    internal fun build(): List<ToastSelectionBoxItem> = items.toList()
}
