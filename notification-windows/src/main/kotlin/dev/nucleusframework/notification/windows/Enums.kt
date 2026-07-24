@file:Suppress("MagicNumber")

package dev.nucleusframework.notification.windows

// -- Activation --

/** How the app is activated when the user interacts with a toast. */
enum class ActivationType(
    val xmlValue: String,
) {
    /** Launch the app in the foreground (default). */
    FOREGROUND("foreground"),

    /** Trigger a background task without showing UI. */
    BACKGROUND("background"),

    /** Launch a different app or URI via protocol. */
    PROTOCOL("protocol"),
}

/** What happens to the toast after it is activated. */
enum class AfterActivationBehavior(
    val xmlValue: String,
) {
    /** Toast is dismissed after activation (default). */
    DEFAULT("default"),

    /** Toast remains visible in a pending-update state. */
    PENDING_UPDATE("pendingUpdate"),
}

/**
 * Toast priority, mapped to WinRT `ToastNotificationPriority` (`IToastNotification4::put_Priority`).
 *
 * Only meaningful within a session; Windows exposes just two levels.
 */
enum class ToastNotificationPriority(
    val nativeValue: Int,
) {
    /** Default priority. */
    DEFAULT(0),

    /** High priority — delivered even under Focus Assist where the app is allowed. */
    HIGH(1),
}

// -- Scenarios --

/** Pre-defined toast display/audio behavior scenarios. */
enum class ToastScenario(
    val xmlValue: String,
) {
    /** Normal toast behavior. */
    DEFAULT("default"),

    /**
     * Important notification: distinct visual treatment and breaks through Focus Assist /
     * Do Not Disturb. Requires Windows 10 build 19041+; ignored on older builds.
     */
    URGENT("urgent"),

    /** Pre-expanded, stays on screen until dismissed. */
    REMINDER("reminder"),

    /** Pre-expanded, stays on screen, audio loops with alarm sound. */
    ALARM("alarm"),

    /** Pre-expanded in special call format, audio loops with ringtone. */
    INCOMING_CALL("incomingCall"),
}

// -- Dismissal --

/** Reason a toast was dismissed. */
enum class DismissalReason(
    val rawValue: Int,
) {
    /** User explicitly dismissed the toast. */
    USER_CANCELED(0),

    /** App programmatically hid the toast. */
    APPLICATION_HIDDEN(1),

    /** Toast timed out and disappeared. */
    TIMED_OUT(2),
    ;

    companion object {
        fun fromRawValue(value: Int): DismissalReason = entries.firstOrNull { it.rawValue == value } ?: TIMED_OUT
    }
}

// -- Text styles --

/** Adaptive text styles for toast content. */
enum class AdaptiveTextStyle(
    val xmlValue: String,
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
enum class AdaptiveTextAlign(
    val xmlValue: String,
) {
    DEFAULT(""),
    AUTO("auto"),
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
}

// -- Image crop --

/** How to crop an image in a toast. */
enum class AdaptiveImageCrop(
    val xmlValue: String,
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
enum class AdaptiveImageAlign(
    val xmlValue: String,
) {
    DEFAULT(""),
    STRETCH("stretch"),
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
}

// -- Image placement --

/** Where to place an image in the toast. */
enum class ImagePlacement(
    val xmlValue: String,
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
enum class AdaptiveSubgroupTextStacking(
    val xmlValue: String,
) {
    DEFAULT(""),
    TOP("top"),
    CENTER("center"),
    BOTTOM("bottom"),
}

// -- Audio --

/** Pre-defined Windows notification sounds. */
enum class ToastAudioSource(
    val uri: String,
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
