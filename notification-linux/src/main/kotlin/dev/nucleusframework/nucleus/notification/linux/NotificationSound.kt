package dev.nucleusframework.nucleus.notification.linux

/**
 * Typesafe representation of a notification sound name.
 *
 * Standard sound names are grouped by category as defined in the
 * [freedesktop Sound Naming Specification](https://specifications.freedesktop.org/sound-naming/latest/).
 *
 * Use [Custom] for sound names not covered by the spec.
 */
sealed interface NotificationSound {
    /** The sound name string sent over D-Bus via the `sound-name` hint. */
    val value: String

    /**
     * A custom sound name not in the freedesktop specification.
     *
     * ```kotlin
     * NotificationSound.Custom("x-myapp-new-order")
     * ```
     */
    @JvmInline
    value class Custom(
        override val value: String,
    ) : NotificationSound

    // ── Alerts ──────────────────────────────────────────────────────
    enum class Alert(
        override val value: String,
    ) : NotificationSound {
        NETWORK_CONNECTIVITY_LOST("network-connectivity-lost"),
        NETWORK_CONNECTIVITY_ERROR("network-connectivity-error"),
        DIALOG_ERROR("dialog-error"),
        BATTERY_LOW("battery-low"),
        SUSPEND_ERROR("suspend-error"),
        SOFTWARE_UPDATE_URGENT("software-update-urgent"),
        POWER_UNPLUG_BATTERY_LOW("power-unplug-battery-low"),
    }

    // ── Notifications ───────────────────────────────────────────────
    enum class Notification(
        override val value: String,
    ) : NotificationSound {
        MESSAGE_NEW_INSTANT("message-new-instant"),
        MESSAGE_NEW_EMAIL("message-new-email"),
        COMPLETE_MEDIA_BURN("complete-media-burn"),
        COMPLETE_MEDIA_RIP("complete-media-rip"),
        COMPLETE_DOWNLOAD("complete-download"),
        COMPLETE_COPY("complete-copy"),
        COMPLETE_SCAN("complete-scan"),
        PHONE_INCOMING_CALL("phone-incoming-call"),
        PHONE_OUTGOING_BUSY("phone-outgoing-busy"),
        PHONE_HANGUP("phone-hangup"),
        PHONE_FAILURE("phone-failure"),
        NETWORK_CONNECTIVITY_ESTABLISHED("network-connectivity-established"),
        SYSTEM_BOOTUP("system-bootup"),
        SYSTEM_READY("system-ready"),
        SYSTEM_SHUTDOWN("system-shutdown"),
        SEARCH_RESULTS("search-results"),
        SEARCH_RESULTS_EMPTY("search-results-empty"),
        DESKTOP_LOGIN("desktop-login"),
        DESKTOP_LOGOUT("desktop-logout"),
        DESKTOP_SCREEN_LOCK("desktop-screen-lock"),
        SERVICE_LOGIN("service-login"),
        SERVICE_LOGOUT("service-logout"),
        BATTERY_CAUTION("battery-caution"),
        BATTERY_FULL("battery-full"),
        DIALOG_WARNING("dialog-warning"),
        DIALOG_INFORMATION("dialog-information"),
        DIALOG_QUESTION("dialog-question"),
        SOFTWARE_UPDATE_AVAILABLE("software-update-available"),
        DEVICE_ADDED("device-added"),
        DEVICE_REMOVED("device-removed"),
        WINDOW_NEW("window-new"),
        POWER_PLUG("power-plug"),
        POWER_UNPLUG("power-unplug"),
        SUSPEND_START("suspend-start"),
        SUSPEND_RESUME("suspend-resume"),
        LID_OPEN("lid-open"),
        LID_CLOSE("lid-close"),
        ALARM_CLOCK_ELAPSED("alarm-clock-elapsed"),
        WINDOW_ATTENTION_ACTIVE("window-attention-active"),
        WINDOW_ATTENTION_INACTIVE("window-attention-inactive"),
    }

    // ── Actions ─────────────────────────────────────────────────────
    enum class Action(
        override val value: String,
    ) : NotificationSound {
        PHONE_OUTGOING_CALLING("phone-outgoing-calling"),
        MESSAGE_SENT_INSTANT("message-sent-instant"),
        MESSAGE_SENT_EMAIL("message-sent-email"),
        BELL_TERMINAL("bell-terminal"),
        BELL_WINDOW_SYSTEM("bell-window-system"),
        TRASH_EMPTY("trash-empty"),
        ITEM_DELETED("item-deleted"),
        FILE_TRASH("file-trash"),
        CAMERA_SHUTTER("camera-shutter"),
        CAMERA_FOCUS("camera-focus"),
        SCREEN_CAPTURE("screen-capture"),
        COUNT_DOWN("count-down"),
        COMPLETION_SUCCESS("completion-sucess"),
        COMPLETION_FAIL("completion-fail"),
        COMPLETION_PARTIAL("completion-partial"),
        COMPLETION_ROTATION("completion-rotation"),
        AUDIO_VOLUME_CHANGE("audio-volume-change"),
        AUDIO_CHANNEL_LEFT("audio-channel-left"),
        AUDIO_CHANNEL_RIGHT("audio-channel-right"),
        AUDIO_CHANNEL_FRONT_LEFT("audio-channel-front-left"),
        AUDIO_CHANNEL_FRONT_RIGHT("audio-channel-front-right"),
        AUDIO_CHANNEL_FRONT_CENTER("audio-channel-front-center"),
        AUDIO_CHANNEL_REAR_LEFT("audio-channel-rear-left"),
        AUDIO_CHANNEL_REAR_RIGHT("audio-channel-rear-right"),
        AUDIO_CHANNEL_REAR_CENTER("audio-channel-rear-center"),
        AUDIO_CHANNEL_LFE("audio-channel-lfe"),
        AUDIO_CHANNEL_SIDE_LEFT("audio-channel-side-left"),
        AUDIO_CHANNEL_SIDE_RIGHT("audio-channel-side-right"),
        AUDIO_TEST_SIGNAL("audio-test-signal"),
    }

    // ── Input Feedback ──────────────────────────────────────────────
    enum class InputFeedback(
        override val value: String,
    ) : NotificationSound {
        WINDOW_CLOSE("window-close"),
        WINDOW_SLIDE_IN("window-slide-in"),
        WINDOW_SLIDE_OUT("window-slide-out"),
        WINDOW_MINIMIZED("window-minimized"),
        WINDOW_UNMINIMIZED("window-unminimized"),
        WINDOW_MAXIMIZED("window-maximized"),
        WINDOW_UNMAXIMIZED("window-unmaximized"),
        WINDOW_INACTIVE_CLICK("window-inactive-click"),
        WINDOW_MOVE_START("window-move-start"),
        WINDOW_MOVE_END("window-move-end"),
        WINDOW_RESIZE_START("window-resize-start"),
        WINDOW_RESIZE_END("window-resize-end"),
        DESKTOP_SWITCH_LEFT("desktop-switch-left"),
        DESKTOP_SWITCH_RIGHT("desktop-switch-right"),
        WINDOW_SWITCH("window-switch"),
        NOTEBOOK_TAB_CHANGED("notebook-tab-changed"),
        SCROLL_UP("scroll-up"),
        SCROLL_DOWN("scroll-down"),
        SCROLL_LEFT("scroll-left"),
        SCROLL_RIGHT("scroll-right"),
        SCROLL_UP_END("scroll-up-end"),
        SCROLL_DOWN_END("scroll-down-end"),
        SCROLL_LEFT_END("scroll-left-end"),
        SCROLL_RIGHT_END("scroll-right-end"),
        DIALOG_OK("dialog-ok"),
        DIALOG_CANCEL("dialog-cancel"),
        DRAG_START("drag-start"),
        DRAG_ACCEPT("drag-accept"),
        DRAG_FAIL("drag-fail"),
        LINK_PRESSED("link-pressed"),
        LINK_RELEASED("link-released"),
        BUTTON_PRESSED("button-pressed"),
        BUTTON_RELEASED("button-released"),
        MENU_CLICK("menu-click"),
        BUTTON_TOGGLE_ON("button-toggle-on"),
        BUTTON_TOGGLE_OFF("button-toggle-off"),
        EXPANDER_TOGGLE_ON("expander-toggle-on"),
        EXPANDER_TOGGLE_OFF("expander-toggle-off"),
        MENU_POPUP("menu-popup"),
        MENU_POPDOWN("menu-popdown"),
        MENU_REPLACE("menu-replace"),
        TOOLTIP_POPUP("tooltip-popup"),
        TOOLTIP_POPDOWN("tooltip-popdown"),
        ITEM_SELECTED("item-selected"),
    }

    // ── Game ────────────────────────────────────────────────────────
    enum class Game(
        override val value: String,
    ) : NotificationSound {
        GAME_OVER_WINNER("game-over-winner"),
        GAME_OVER_LOSER("game-over-loser"),
        GAME_CARD_SHUFFLE("game-card-shuffle"),
        GAME_HUMAN_MOVE("game-human-move"),
        GAME_COMPUTER_MOVE("game-computer-move"),
    }
}
