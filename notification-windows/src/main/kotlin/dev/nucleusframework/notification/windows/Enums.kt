@file:Suppress("MagicNumber")

package dev.nucleusframework.notification.windows

// -- Activation --

/** How the app is activated when the user interacts with a toast. */
public enum class ActivationType(
    public val xmlValue: String,
) {
    /** Launch the app in the foreground (default). */
    FOREGROUND("foreground"),

    /** Trigger a background task without showing UI. */
    BACKGROUND("background"),

    /** Launch a different app or URI via protocol. */
    PROTOCOL("protocol"),
}

/** What happens to the toast after it is activated. */
public enum class AfterActivationBehavior(
    public val xmlValue: String,
) {
    /** Toast is dismissed after activation (default). */
    DEFAULT("default"),

    /** Toast remains visible in a pending-update state. */
    PENDING_UPDATE("pendingUpdate"),
}

// -- Scenarios --

/** Pre-defined toast display/audio behavior scenarios. */
public enum class ToastScenario(
    public val xmlValue: String,
) {
    /** Normal toast behavior. */
    DEFAULT("default"),

    /** Pre-expanded, stays on screen until dismissed. */
    REMINDER("reminder"),

    /** Pre-expanded, stays on screen, audio loops with alarm sound. */
    ALARM("alarm"),

    /** Pre-expanded in special call format, audio loops with ringtone. */
    INCOMING_CALL("incomingCall"),

    /**
     * High-priority toast that can break through Focus Assist / Do Not Disturb.
     *
     * Requires Windows 11 (build 22000+); ignored on older versions.
     */
    URGENT("urgent"),
}

/** How long a toast stays on screen before moving to the Action Center. */
public enum class ToastDuration(
    public val xmlValue: String,
) {
    /** System default (roughly 7 seconds). */
    DEFAULT(""),

    /** Short duration (roughly 7 seconds). */
    SHORT("short"),

    /** Long duration (roughly 25 seconds). */
    LONG("long"),
}

// -- Dismissal --

/** Reason a toast was dismissed. */
public enum class DismissalReason(
    public val rawValue: Int,
) {
    /** User explicitly dismissed the toast. */
    USER_CANCELED(0),

    /** App programmatically hid the toast. */
    APPLICATION_HIDDEN(1),

    /** Toast timed out and disappeared. */
    TIMED_OUT(2),
    ;

    public companion object {
        public fun fromRawValue(value: Int): DismissalReason = entries.firstOrNull { it.rawValue == value } ?: TIMED_OUT
    }
}

// -- Text styles --

/** Adaptive text styles for toast content. */
public enum class AdaptiveTextStyle(
    public val xmlValue: String,
) {
    DEFAULT(""),
    CAPTION("caption"),
    CAPTION_SUBTLE("captionSubtle"),
    BODY("body"),
    BODY_SUBTLE("bodySubtle"),
    BASE("base"),
    BASE_SUBTLE("baseSubtle"),
    SUBTITLE("subtitle"),
    SUBTITLE_SUBTLE("subtitleSubtle"),
    TITLE("title"),
    TITLE_SUBTLE("titleSubtle"),
    TITLE_NUMERAL("titleNumeral"),
    SUBHEADER("subheader"),
    SUBHEADER_SUBTLE("subheaderSubtle"),
    SUBHEADER_NUMERAL("subheaderNumeral"),
    HEADER("header"),
    HEADER_SUBTLE("headerSubtle"),
    HEADER_NUMERAL("headerNumeral"),
}

// -- Text alignment --

/** Horizontal alignment for text within groups. */
public enum class AdaptiveTextAlign(
    public val xmlValue: String,
) {
    DEFAULT(""),
    AUTO("auto"),
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
}

// -- Image crop --

/** How to crop an image in a toast. */
public enum class AdaptiveImageCrop(
    public val xmlValue: String,
) {
    /** Default square/rectangular crop. */
    DEFAULT(""),

    /** No cropping. */
    NONE("none"),

    /** Circular crop. */
    CIRCLE("circle"),
}

// -- Image alignment --

/** Horizontal alignment for images within groups. */
public enum class AdaptiveImageAlign(
    public val xmlValue: String,
) {
    DEFAULT(""),
    STRETCH("stretch"),
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
}

// -- Image placement --

/** Where to place an image in the toast. */
public enum class ImagePlacement(
    public val xmlValue: String,
) {
    /** Inline within the toast body. */
    INLINE(""),

    /** Override the app logo (left of text). */
    APP_LOGO_OVERRIDE("appLogoOverride"),

    /** Large hero image at top of toast. */
    HERO("hero"),
}

// -- Subgroup text stacking --

/** Vertical alignment of text content within a subgroup column. */
public enum class AdaptiveSubgroupTextStacking(
    public val xmlValue: String,
) {
    DEFAULT(""),
    TOP("top"),
    CENTER("center"),
    BOTTOM("bottom"),
}

// -- Audio --

/** Pre-defined Windows notification sounds. */
public enum class ToastAudioSource(
    public val uri: String,
) {
    DEFAULT("ms-winsoundevent:Notification.Default"),
    IM("ms-winsoundevent:Notification.IM"),
    MAIL("ms-winsoundevent:Notification.Mail"),
    REMINDER("ms-winsoundevent:Notification.Reminder"),
    SMS("ms-winsoundevent:Notification.SMS"),

    ALARM_DEFAULT("ms-winsoundevent:Notification.Looping.Alarm"),
    ALARM2("ms-winsoundevent:Notification.Looping.Alarm2"),
    ALARM3("ms-winsoundevent:Notification.Looping.Alarm3"),
    ALARM4("ms-winsoundevent:Notification.Looping.Alarm4"),
    ALARM5("ms-winsoundevent:Notification.Looping.Alarm5"),
    ALARM6("ms-winsoundevent:Notification.Looping.Alarm6"),
    ALARM7("ms-winsoundevent:Notification.Looping.Alarm7"),
    ALARM8("ms-winsoundevent:Notification.Looping.Alarm8"),
    ALARM9("ms-winsoundevent:Notification.Looping.Alarm9"),
    ALARM10("ms-winsoundevent:Notification.Looping.Alarm10"),

    CALL_DEFAULT("ms-winsoundevent:Notification.Looping.Call"),
    CALL2("ms-winsoundevent:Notification.Looping.Call2"),
    CALL3("ms-winsoundevent:Notification.Looping.Call3"),
    CALL4("ms-winsoundevent:Notification.Looping.Call4"),
    CALL5("ms-winsoundevent:Notification.Looping.Call5"),
    CALL6("ms-winsoundevent:Notification.Looping.Call6"),
    CALL7("ms-winsoundevent:Notification.Looping.Call7"),
    CALL8("ms-winsoundevent:Notification.Looping.Call8"),
    CALL9("ms-winsoundevent:Notification.Looping.Call9"),
    CALL10("ms-winsoundevent:Notification.Looping.Call10"),
}
