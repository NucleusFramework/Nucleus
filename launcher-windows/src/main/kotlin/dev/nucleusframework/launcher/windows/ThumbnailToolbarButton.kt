package dev.nucleusframework.launcher.windows

/**
 * A button on the Windows taskbar thumbnail toolbar.
 *
 * Up to 7 buttons can be added per window. Once added, buttons cannot be removed —
 * only their state (icon, tooltip, flags) can be updated. Use [hidden] to hide a button.
 *
 * @param id     Unique button identifier (0–6). Used to identify the button in click callbacks.
 * @param tooltip Hover text (max 259 characters).
 * @param icon    Icon source, or null for no icon.
 * @param enabled Whether the button is clickable.
 * @param hidden  Whether the button is invisible.
 * @param noBackground Whether to hide the button border/background.
 * @param dismissOnClick Whether clicking the button closes the thumbnail preview.
 * @param nonInteractive Whether the button is visible but not clickable (no hover/pressed state).
 */
public data class ThumbnailToolbarButton(
    val id: Int,
    val tooltip: String = "",
    val icon: TaskbarIconSource? = null,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
    val noBackground: Boolean = false,
    val dismissOnClick: Boolean = false,
    val nonInteractive: Boolean = false,
) {
    init {
        require(id in 0..MAX_BUTTON_INDEX) { "Button id must be 0–$MAX_BUTTON_INDEX, got $id" }
        require(tooltip.length <= MAX_TOOLTIP_LENGTH) { "Tooltip exceeds $MAX_TOOLTIP_LENGTH characters" }
    }

    public companion object {
        public const val MAX_BUTTONS: Int = 7
        private const val MAX_BUTTON_INDEX = 6
        private const val MAX_TOOLTIP_LENGTH = 259
    }
}

@Suppress("MagicNumber")
internal fun ThumbnailToolbarButton.toNativeFlags(): Int {
    var flags = 0
    if (!enabled) flags = flags or 0x01 // THBF_DISABLED
    if (dismissOnClick) flags = flags or 0x02 // THBF_DISMISSONCLICK
    if (noBackground) flags = flags or 0x04 // THBF_NOBACKGROUND
    if (hidden) flags = flags or 0x08 // THBF_HIDDEN
    if (nonInteractive) flags = flags or 0x10 // THBF_NONINTERACTIVE
    return flags
}
