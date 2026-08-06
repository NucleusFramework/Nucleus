package dev.nucleusframework.launcher.windows

/**
 * Predefined badge glyph icons for Windows badge notifications.
 *
 * These glyphs appear as small icons on the app's taskbar button and Start tile.
 * Each glyph conveys a specific status or state to the user.
 *
 * @property value The XML value string used in the badge XML schema.
 */
public enum class BadgeGlyph(
    public val value: String,
) {
    NONE("none"),
    ACTIVITY("activity"),
    ALARM("alarm"),
    ALERT("alert"),
    ATTENTION("attention"),
    AVAILABLE("available"),
    AWAY("away"),
    BUSY("busy"),
    ERROR("error"),
    NEW_MESSAGE("newMessage"),
    PAUSED("paused"),
    PLAYING("playing"),
    UNAVAILABLE("unavailable"),
}
