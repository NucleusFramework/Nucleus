package dev.nucleusframework.notification.windows

/**
 * Builds the Windows toast notification XML from a [ToastContent] model.
 *
 * The generated XML conforms to the official toast schema:
 * https://learn.microsoft.com/en-us/windows/apps/develop/notifications/app-notifications/toast-schema
 */
@Suppress("TooManyFunctions")
internal object ToastXmlBuilder {
    fun buildXml(toast: ToastContent): String {
        val sb = StringBuilder()
        sb.append("<toast")
        if (toast.launch.isNotEmpty()) sb.attr("launch", toast.launch)
        if (toast.activationType != ActivationType.FOREGROUND) {
            sb.attr("activationType", toast.activationType.xmlValue)
        }
        if (toast.scenario != ToastScenario.DEFAULT) {
            sb.attr("scenario", toast.scenario.xmlValue)
        }
        if (toast.duration != ToastDuration.DEFAULT) {
            sb.attr("duration", toast.duration.xmlValue)
        }
        toast.displayTimestamp?.let { sb.attr("displayTimestamp", it) }
        sb.append(">")

        appendVisual(sb, toast.visual)
        toast.actions?.let { appendActions(sb, it) }
        toast.audio?.let { appendAudio(sb, it) }
        toast.header?.let { appendHeader(sb, it) }

        sb.append("</toast>")
        return sb.toString()
    }

    // -- Visual --

    private fun appendVisual(
        sb: StringBuilder,
        visual: ToastVisual,
    ) {
        sb.append("<visual>")
        appendBinding(sb, visual.binding)
        sb.append("</visual>")
    }

    private fun appendBinding(
        sb: StringBuilder,
        binding: ToastBindingGeneric,
    ) {
        sb.append("<binding template=\"ToastGeneric\">")

        for (child in binding.children) {
            when (child) {
                is AdaptiveText -> appendAdaptiveText(sb, child)
                is AdaptiveImage -> appendAdaptiveImage(sb, child, ImagePlacement.INLINE)
                is AdaptiveGroup -> appendGroup(sb, child)
                is AdaptiveProgressBar -> appendProgressBar(sb, child)
            }
        }

        binding.appLogoOverride?.let { appendAppLogo(sb, it) }
        binding.heroImage?.let { appendHeroImage(sb, it) }
        binding.attribution?.let { appendAttribution(sb, it) }

        sb.append("</binding>")
    }

    private fun appendAdaptiveText(
        sb: StringBuilder,
        text: AdaptiveText,
    ) {
        sb.append("<text")
        if (text.hintStyle != AdaptiveTextStyle.DEFAULT) sb.attr("hint-style", text.hintStyle.xmlValue)
        text.hintWrap?.let { sb.attr("hint-wrap", it.toString()) }
        text.hintMaxLines?.let { sb.attr("hint-maxLines", it.toString()) }
        text.hintMinLines?.let { sb.attr("hint-minLines", it.toString()) }
        if (text.hintAlign != AdaptiveTextAlign.DEFAULT) sb.attr("hint-align", text.hintAlign.xmlValue)
        text.language?.let { sb.attr("lang", it) }
        sb.append(">")
        sb.append(escapeXml(text.text))
        sb.append("</text>")
    }

    private fun appendAdaptiveImage(
        sb: StringBuilder,
        image: AdaptiveImage,
        placement: ImagePlacement,
    ) {
        sb.append("<image")
        sb.attr("src", image.source)
        if (placement != ImagePlacement.INLINE) sb.attr("placement", placement.xmlValue)
        if (image.hintCrop != AdaptiveImageCrop.DEFAULT) sb.attr("hint-crop", image.hintCrop.xmlValue)
        image.hintRemoveMargin?.let { sb.attr("hint-removeMargin", it.toString()) }
        if (image.hintAlign != AdaptiveImageAlign.DEFAULT) sb.attr("hint-align", image.hintAlign.xmlValue)
        image.alternateText?.let { sb.attr("alt", it) }
        image.addImageQuery?.let { sb.attr("addImageQuery", it.toString()) }
        sb.append("/>")
    }

    private fun appendAppLogo(
        sb: StringBuilder,
        logo: ToastGenericAppLogo,
    ) {
        sb.append("<image")
        sb.attr("src", logo.source)
        sb.attr("placement", ImagePlacement.APP_LOGO_OVERRIDE.xmlValue)
        if (logo.hintCrop != AdaptiveImageCrop.DEFAULT) sb.attr("hint-crop", logo.hintCrop.xmlValue)
        logo.alternateText?.let { sb.attr("alt", it) }
        logo.addImageQuery?.let { sb.attr("addImageQuery", it.toString()) }
        sb.append("/>")
    }

    private fun appendHeroImage(
        sb: StringBuilder,
        hero: ToastGenericHeroImage,
    ) {
        sb.append("<image")
        sb.attr("src", hero.source)
        sb.attr("placement", ImagePlacement.HERO.xmlValue)
        hero.alternateText?.let { sb.attr("alt", it) }
        hero.addImageQuery?.let { sb.attr("addImageQuery", it.toString()) }
        sb.append("/>")
    }

    private fun appendAttribution(
        sb: StringBuilder,
        attr: ToastGenericAttributionText,
    ) {
        sb.append("<text")
        sb.attr("placement", "attribution")
        attr.language?.let { sb.attr("lang", it) }
        sb.append(">")
        sb.append(escapeXml(attr.text))
        sb.append("</text>")
    }

    // -- Groups & subgroups --

    private fun appendGroup(
        sb: StringBuilder,
        group: AdaptiveGroup,
    ) {
        sb.append("<group>")
        for (subgroup in group.subgroups) {
            appendSubgroup(sb, subgroup)
        }
        sb.append("</group>")
    }

    private fun appendSubgroup(
        sb: StringBuilder,
        subgroup: AdaptiveSubgroup,
    ) {
        sb.append("<subgroup")
        subgroup.hintWeight?.let { sb.attr("hint-weight", it.toString()) }
        if (subgroup.hintTextStacking != AdaptiveSubgroupTextStacking.DEFAULT) {
            sb.attr("hint-textStacking", subgroup.hintTextStacking.xmlValue)
        }
        sb.append(">")
        for (child in subgroup.children) {
            when (child) {
                is AdaptiveText -> appendAdaptiveText(sb, child)
                is AdaptiveImage -> appendAdaptiveImage(sb, child, ImagePlacement.INLINE)
            }
        }
        sb.append("</subgroup>")
    }

    // -- Progress bar --

    private fun appendProgressBar(
        sb: StringBuilder,
        bar: AdaptiveProgressBar,
    ) {
        sb.append("<progress")
        bar.title?.let { sb.attr("title", it) }
        when {
            bar.valueBind != null -> sb.attr("value", "{${bar.valueBind}}")
            bar.value != null -> sb.attr("value", bar.value.toString())
            else -> sb.attr("value", "indeterminate")
        }
        bar.valueStringOverride?.let { sb.attr("valueStringOverride", it) }
        sb.attr("status", bar.status)
        sb.append("/>")
    }

    // -- Actions --

    private fun appendActions(
        sb: StringBuilder,
        actions: ToastActions,
    ) {
        sb.append("<actions>")

        for (input in actions.inputs) {
            when (input) {
                is ToastTextBox -> appendTextBox(sb, input)
                is ToastSelectionBox -> appendSelectionBox(sb, input)
            }
        }

        for (button in actions.buttons) {
            appendButton(sb, button)
        }

        for (item in actions.contextMenuItems) {
            appendContextMenuItem(sb, item)
        }

        sb.append("</actions>")
    }

    private fun appendTextBox(
        sb: StringBuilder,
        textBox: ToastTextBox,
    ) {
        sb.append("<input")
        sb.attr("id", textBox.id)
        sb.attr("type", "text")
        textBox.title?.let { sb.attr("title", it) }
        textBox.placeholderContent?.let { sb.attr("placeHolderContent", it) }
        textBox.defaultInput?.let { sb.attr("defaultInput", it) }
        sb.append("/>")
    }

    private fun appendSelectionBox(
        sb: StringBuilder,
        box: ToastSelectionBox,
    ) {
        sb.append("<input")
        sb.attr("id", box.id)
        sb.attr("type", "selection")
        box.title?.let { sb.attr("title", it) }
        box.defaultSelectionBoxItemId?.let { sb.attr("defaultInput", it) }
        sb.append(">")
        for (item in box.items) {
            sb.append("<selection")
            sb.attr("id", item.id)
            sb.attr("content", item.content)
            sb.append("/>")
        }
        sb.append("</input>")
    }

    private fun appendButton(
        sb: StringBuilder,
        button: ToastButton,
    ) {
        sb.append("<action")
        sb.attr("content", button.content)
        sb.attr("arguments", button.arguments)
        if (button.activationType != ActivationType.FOREGROUND) {
            sb.attr("activationType", button.activationType.xmlValue)
        }
        button.imageUri?.let { sb.attr("imageUri", it) }
        button.inputId?.let { sb.attr("hint-inputId", it) }
        if (button.afterActivationBehavior != AfterActivationBehavior.DEFAULT) {
            sb.attr("afterActivationBehavior", button.afterActivationBehavior.xmlValue)
        }
        button.tooltipText?.let { sb.attr("hint-toolTip", it) }
        sb.append("/>")
    }

    private fun appendContextMenuItem(
        sb: StringBuilder,
        item: ToastContextMenuItem,
    ) {
        sb.append("<action")
        sb.attr("content", item.content)
        sb.attr("arguments", item.arguments)
        sb.attr("placement", "contextMenu")
        if (item.activationType != ActivationType.FOREGROUND) {
            sb.attr("activationType", item.activationType.xmlValue)
        }
        sb.append("/>")
    }

    // -- Audio --

    private fun appendAudio(
        sb: StringBuilder,
        audio: ToastAudio,
    ) {
        sb.append("<audio")
        if (audio.silent) {
            sb.attr("silent", "true")
        } else {
            val src = audio.customSource ?: audio.source?.uri
            src?.let { sb.attr("src", it) }
            if (audio.loop) sb.attr("loop", "true")
        }
        sb.append("/>")
    }

    // -- Header --

    private fun appendHeader(
        sb: StringBuilder,
        header: ToastHeader,
    ) {
        sb.append("<header")
        sb.attr("id", header.id)
        sb.attr("title", header.title)
        sb.attr("arguments", header.arguments)
        if (header.activationType != ActivationType.FOREGROUND) {
            sb.attr("activationType", header.activationType.xmlValue)
        }
        sb.append("/>")
    }

    // -- Helpers --

    private fun StringBuilder.attr(
        name: String,
        value: String,
    ) {
        append(" ")
            .append(name)
            .append("=\"")
            .append(escapeXml(value))
            .append("\"")
    }

    private fun escapeXml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
