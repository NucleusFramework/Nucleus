package dev.nucleusframework.freedesktop.icons

/**
 * Type-safe representation of a freedesktop icon name.
 *
 * Standard icon names are grouped by context as defined in the
 * [freedesktop Icon Naming Specification](https://specifications.freedesktop.org/icon-naming/latest/).
 *
 * Use [Custom] for icon names not covered by the spec, file paths, or `file://` URIs.
 */
public sealed interface FreedesktopIcon {
    /** The icon name, file path, or URI string sent over D-Bus. */
    public val value: String

    /**
     * A custom icon name, absolute file path, or `file://` URI.
     *
     * ```kotlin
     * FreedesktopIcon.Custom("my-app-icon")
     * FreedesktopIcon.Custom("/home/user/icon.png")
     * FreedesktopIcon.Custom("file:///home/user/icon.png")
     * ```
     */
    @JvmInline
    public value class Custom(
        override val value: String,
    ) : FreedesktopIcon

    public companion object {
        /**
         * Returns a country flag icon for the given ISO 3166-1 alpha-2 country code.
         *
         * ```kotlin
         * FreedesktopIcon.flag("fr") // flag-fr
         * ```
         */
        public fun flag(countryCode: String): FreedesktopIcon = Custom("flag-${countryCode.lowercase()}")
    }

    // ── Actions ─────────────────────────────────────────────────────
    public enum class Action(
        override val value: String,
    ) : FreedesktopIcon {
        ADDRESS_BOOK_NEW("address-book-new"),
        APPLICATION_EXIT("application-exit"),
        APPOINTMENT_NEW("appointment-new"),
        CALL_START("call-start"),
        CALL_STOP("call-stop"),
        CONTACT_NEW("contact-new"),
        DOCUMENT_NEW("document-new"),
        DOCUMENT_OPEN("document-open"),
        DOCUMENT_OPEN_RECENT("document-open-recent"),
        DOCUMENT_PAGE_SETUP("document-page-setup"),
        DOCUMENT_PRINT("document-print"),
        DOCUMENT_PRINT_PREVIEW("document-print-preview"),
        DOCUMENT_PROPERTIES("document-properties"),
        DOCUMENT_REVERT("document-revert"),
        DOCUMENT_SAVE("document-save"),
        DOCUMENT_SAVE_AS("document-save-as"),
        DOCUMENT_SEND("document-send"),
        EDIT_CLEAR("edit-clear"),
        EDIT_COPY("edit-copy"),
        EDIT_CUT("edit-cut"),
        EDIT_DELETE("edit-delete"),
        EDIT_FIND("edit-find"),
        EDIT_FIND_REPLACE("edit-find-replace"),
        EDIT_PASTE("edit-paste"),
        EDIT_REDO("edit-redo"),
        EDIT_SELECT_ALL("edit-select-all"),
        EDIT_UNDO("edit-undo"),
        FIND_LOCATION("find-location"),
        FOLDER_NEW("folder-new"),
        FORMAT_INDENT_LESS("format-indent-less"),
        FORMAT_INDENT_MORE("format-indent-more"),
        FORMAT_JUSTIFY_CENTER("format-justify-center"),
        FORMAT_JUSTIFY_FILL("format-justify-fill"),
        FORMAT_JUSTIFY_LEFT("format-justify-left"),
        FORMAT_JUSTIFY_RIGHT("format-justify-right"),
        FORMAT_TEXT_DIRECTION_LTR("format-text-direction-ltr"),
        FORMAT_TEXT_DIRECTION_RTL("format-text-direction-rtl"),
        FORMAT_TEXT_BOLD("format-text-bold"),
        FORMAT_TEXT_ITALIC("format-text-italic"),
        FORMAT_TEXT_UNDERLINE("format-text-underline"),
        FORMAT_TEXT_STRIKETHROUGH("format-text-strikethrough"),
        GO_BOTTOM("go-bottom"),
        GO_DOWN("go-down"),
        GO_FIRST("go-first"),
        GO_HOME("go-home"),
        GO_JUMP("go-jump"),
        GO_LAST("go-last"),
        GO_NEXT("go-next"),
        GO_PREVIOUS("go-previous"),
        GO_TOP("go-top"),
        GO_UP("go-up"),
        HELP_ABOUT("help-about"),
        HELP_CONTENTS("help-contents"),
        HELP_FAQ("help-faq"),
        INSERT_IMAGE("insert-image"),
        INSERT_LINK("insert-link"),
        INSERT_OBJECT("insert-object"),
        INSERT_TEXT("insert-text"),
        LIST_ADD("list-add"),
        LIST_REMOVE("list-remove"),
        MAIL_FORWARD("mail-forward"),
        MAIL_MARK_IMPORTANT("mail-mark-important"),
        MAIL_MARK_JUNK("mail-mark-junk"),
        MAIL_MARK_NOTJUNK("mail-mark-notjunk"),
        MAIL_MARK_READ("mail-mark-read"),
        MAIL_MARK_UNREAD("mail-mark-unread"),
        MAIL_MESSAGE_NEW("mail-message-new"),
        MAIL_REPLY_ALL("mail-reply-all"),
        MAIL_REPLY_SENDER("mail-reply-sender"),
        MAIL_SEND("mail-send"),
        MAIL_SEND_RECEIVE("mail-send-receive"),
        MEDIA_EJECT("media-eject"),
        MEDIA_PLAYBACK_PAUSE("media-playback-pause"),
        MEDIA_PLAYBACK_START("media-playback-start"),
        MEDIA_PLAYBACK_STOP("media-playback-stop"),
        MEDIA_RECORD("media-record"),
        MEDIA_SEEK_BACKWARD("media-seek-backward"),
        MEDIA_SEEK_FORWARD("media-seek-forward"),
        MEDIA_SKIP_BACKWARD("media-skip-backward"),
        MEDIA_SKIP_FORWARD("media-skip-forward"),
        OBJECT_FLIP_HORIZONTAL("object-flip-horizontal"),
        OBJECT_FLIP_VERTICAL("object-flip-vertical"),
        OBJECT_ROTATE_LEFT("object-rotate-left"),
        OBJECT_ROTATE_RIGHT("object-rotate-right"),
        PROCESS_STOP("process-stop"),
        SYSTEM_LOCK_SCREEN("system-lock-screen"),
        SYSTEM_LOG_OUT("system-log-out"),
        SYSTEM_RUN("system-run"),
        SYSTEM_SEARCH("system-search"),
        SYSTEM_REBOOT("system-reboot"),
        SYSTEM_SHUTDOWN("system-shutdown"),
        TOOLS_CHECK_SPELLING("tools-check-spelling"),
        VIEW_FULLSCREEN("view-fullscreen"),
        VIEW_REFRESH("view-refresh"),
        VIEW_RESTORE("view-restore"),
        VIEW_SORT_ASCENDING("view-sort-ascending"),
        VIEW_SORT_DESCENDING("view-sort-descending"),
        WINDOW_CLOSE("window-close"),
        WINDOW_NEW("window-new"),
        ZOOM_FIT_BEST("zoom-fit-best"),
        ZOOM_IN("zoom-in"),
        ZOOM_ORIGINAL("zoom-original"),
        ZOOM_OUT("zoom-out"),
    }

    // ── Animations ──────────────────────────────────────────────────
    public enum class Animation(
        override val value: String,
    ) : FreedesktopIcon {
        PROCESS_WORKING("process-working"),
    }

    // ── Applications ────────────────────────────────────────────────
    public enum class Application(
        override val value: String,
    ) : FreedesktopIcon {
        ACCESSORIES_CALCULATOR("accessories-calculator"),
        ACCESSORIES_CHARACTER_MAP("accessories-character-map"),
        ACCESSORIES_DICTIONARY("accessories-dictionary"),
        ACCESSORIES_SCREENSHOT_TOOL("accessories-screenshot-tool"),
        ACCESSORIES_TEXT_EDITOR("accessories-text-editor"),
        HELP_BROWSER("help-browser"),
        MULTIMEDIA_VOLUME_CONTROL("multimedia-volume-control"),
        PREFERENCES_DESKTOP_ACCESSIBILITY("preferences-desktop-accessibility"),
        PREFERENCES_DESKTOP_FONT("preferences-desktop-font"),
        PREFERENCES_DESKTOP_KEYBOARD("preferences-desktop-keyboard"),
        PREFERENCES_DESKTOP_LOCALE("preferences-desktop-locale"),
        PREFERENCES_DESKTOP_MULTIMEDIA("preferences-desktop-multimedia"),
        PREFERENCES_DESKTOP_SCREENSAVER("preferences-desktop-screensaver"),
        PREFERENCES_DESKTOP_THEME("preferences-desktop-theme"),
        PREFERENCES_DESKTOP_WALLPAPER("preferences-desktop-wallpaper"),
        SYSTEM_FILE_MANAGER("system-file-manager"),
        SYSTEM_SOFTWARE_INSTALL("system-software-install"),
        SYSTEM_SOFTWARE_UPDATE("system-software-update"),
        UTILITIES_SYSTEM_MONITOR("utilities-system-monitor"),
        UTILITIES_TERMINAL("utilities-terminal"),
    }

    // ── Categories ──────────────────────────────────────────────────
    public enum class Category(
        override val value: String,
    ) : FreedesktopIcon {
        APPLICATIONS_ACCESSORIES("applications-accessories"),
        APPLICATIONS_DEVELOPMENT("applications-development"),
        APPLICATIONS_ENGINEERING("applications-engineering"),
        APPLICATIONS_GAMES("applications-games"),
        APPLICATIONS_GRAPHICS("applications-graphics"),
        APPLICATIONS_INTERNET("applications-internet"),
        APPLICATIONS_MULTIMEDIA("applications-multimedia"),
        APPLICATIONS_OFFICE("applications-office"),
        APPLICATIONS_OTHER("applications-other"),
        APPLICATIONS_SCIENCE("applications-science"),
        APPLICATIONS_SYSTEM("applications-system"),
        APPLICATIONS_UTILITIES("applications-utilities"),
        PREFERENCES_DESKTOP("preferences-desktop"),
        PREFERENCES_DESKTOP_PERIPHERALS("preferences-desktop-peripherals"),
        PREFERENCES_DESKTOP_PERSONAL("preferences-desktop-personal"),
        PREFERENCES_OTHER("preferences-other"),
        PREFERENCES_SYSTEM("preferences-system"),
        PREFERENCES_SYSTEM_NETWORK("preferences-system-network"),
        SYSTEM_HELP("system-help"),
    }

    // ── Devices ─────────────────────────────────────────────────────
    public enum class Device(
        override val value: String,
    ) : FreedesktopIcon {
        AUDIO_CARD("audio-card"),
        AUDIO_INPUT_MICROPHONE("audio-input-microphone"),
        BATTERY("battery"),
        CAMERA_PHOTO("camera-photo"),
        CAMERA_VIDEO("camera-video"),
        CAMERA_WEB("camera-web"),
        COMPUTER("computer"),
        DRIVE_HARDDISK("drive-harddisk"),
        DRIVE_OPTICAL("drive-optical"),
        DRIVE_REMOVABLE_MEDIA("drive-removable-media"),
        INPUT_GAMING("input-gaming"),
        INPUT_KEYBOARD("input-keyboard"),
        INPUT_MOUSE("input-mouse"),
        INPUT_TABLET("input-tablet"),
        MEDIA_FLASH("media-flash"),
        MEDIA_FLOPPY("media-floppy"),
        MEDIA_OPTICAL("media-optical"),
        MEDIA_TAPE("media-tape"),
        MODEM("modem"),
        MULTIMEDIA_PLAYER("multimedia-player"),
        NETWORK_WIRED("network-wired"),
        NETWORK_WIRELESS("network-wireless"),
        PDA("pda"),
        PHONE("phone"),
        PRINTER("printer"),
        SCANNER("scanner"),
        VIDEO_DISPLAY("video-display"),
    }

    // ── Emblems ─────────────────────────────────────────────────────
    public enum class Emblem(
        override val value: String,
    ) : FreedesktopIcon {
        DEFAULT("emblem-default"),
        DOCUMENTS("emblem-documents"),
        DOWNLOADS("emblem-downloads"),
        FAVORITE("emblem-favorite"),
        IMPORTANT("emblem-important"),
        MAIL("emblem-mail"),
        PHOTOS("emblem-photos"),
        READONLY("emblem-readonly"),
        SHARED("emblem-shared"),
        SYMBOLIC_LINK("emblem-symbolic-link"),
        SYNCHRONIZED("emblem-synchronized"),
        SYSTEM("emblem-system"),
        UNREADABLE("emblem-unreadable"),
    }

    // ── Emotes ──────────────────────────────────────────────────────
    public enum class Emote(
        override val value: String,
    ) : FreedesktopIcon {
        FACE_ANGEL("face-angel"),
        FACE_ANGRY("face-angry"),
        FACE_COOL("face-cool"),
        FACE_CRYING("face-crying"),
        FACE_DEVILISH("face-devilish"),
        FACE_EMBARRASSED("face-embarrassed"),
        FACE_KISS("face-kiss"),
        FACE_LAUGH("face-laugh"),
        FACE_MONKEY("face-monkey"),
        FACE_PLAIN("face-plain"),
        FACE_RASPBERRY("face-raspberry"),
        FACE_SAD("face-sad"),
        FACE_SICK("face-sick"),
        FACE_SMILE("face-smile"),
        FACE_SMILE_BIG("face-smile-big"),
        FACE_SMIRK("face-smirk"),
        FACE_SURPRISE("face-surprise"),
        FACE_TIRED("face-tired"),
        FACE_UNCERTAIN("face-uncertain"),
        FACE_WINK("face-wink"),
        FACE_WORRIED("face-worried"),
    }

    // ── MIME Types ──────────────────────────────────────────────────
    public enum class MimeType(
        override val value: String,
    ) : FreedesktopIcon {
        APPLICATION_X_EXECUTABLE("application-x-executable"),
        AUDIO_X_GENERIC("audio-x-generic"),
        FONT_X_GENERIC("font-x-generic"),
        IMAGE_X_GENERIC("image-x-generic"),
        PACKAGE_X_GENERIC("package-x-generic"),
        TEXT_HTML("text-html"),
        TEXT_X_GENERIC("text-x-generic"),
        TEXT_X_GENERIC_TEMPLATE("text-x-generic-template"),
        TEXT_X_SCRIPT("text-x-script"),
        VIDEO_X_GENERIC("video-x-generic"),
        X_OFFICE_ADDRESS_BOOK("x-office-address-book"),
        X_OFFICE_CALENDAR("x-office-calendar"),
        X_OFFICE_DOCUMENT("x-office-document"),
        X_OFFICE_PRESENTATION("x-office-presentation"),
        X_OFFICE_SPREADSHEET("x-office-spreadsheet"),
    }

    // ── Places ──────────────────────────────────────────────────────
    public enum class Place(
        override val value: String,
    ) : FreedesktopIcon {
        FOLDER("folder"),
        FOLDER_REMOTE("folder-remote"),
        NETWORK_SERVER("network-server"),
        NETWORK_WORKGROUP("network-workgroup"),
        START_HERE("start-here"),
        USER_BOOKMARKS("user-bookmarks"),
        USER_DESKTOP("user-desktop"),
        USER_HOME("user-home"),
        USER_TRASH("user-trash"),
    }

    // ── Status ──────────────────────────────────────────────────────
    public enum class Status(
        override val value: String,
    ) : FreedesktopIcon {
        APPOINTMENT_MISSED("appointment-missed"),
        APPOINTMENT_SOON("appointment-soon"),
        AUDIO_VOLUME_HIGH("audio-volume-high"),
        AUDIO_VOLUME_LOW("audio-volume-low"),
        AUDIO_VOLUME_MEDIUM("audio-volume-medium"),
        AUDIO_VOLUME_MUTED("audio-volume-muted"),
        BATTERY_CAUTION("battery-caution"),
        BATTERY_LOW("battery-low"),
        DIALOG_ERROR("dialog-error"),
        DIALOG_INFORMATION("dialog-information"),
        DIALOG_PASSWORD("dialog-password"),
        DIALOG_QUESTION("dialog-question"),
        DIALOG_WARNING("dialog-warning"),
        FOLDER_DRAG_ACCEPT("folder-drag-accept"),
        FOLDER_OPEN("folder-open"),
        FOLDER_VISITING("folder-visiting"),
        IMAGE_LOADING("image-loading"),
        IMAGE_MISSING("image-missing"),
        MAIL_ATTACHMENT("mail-attachment"),
        MAIL_UNREAD("mail-unread"),
        MAIL_READ("mail-read"),
        MAIL_REPLIED("mail-replied"),
        MAIL_SIGNED("mail-signed"),
        MAIL_SIGNED_VERIFIED("mail-signed-verified"),
        MEDIA_PLAYLIST_REPEAT("media-playlist-repeat"),
        MEDIA_PLAYLIST_SHUFFLE("media-playlist-shuffle"),
        NETWORK_ERROR("network-error"),
        NETWORK_IDLE("network-idle"),
        NETWORK_OFFLINE("network-offline"),
        NETWORK_RECEIVE("network-receive"),
        NETWORK_TRANSMIT("network-transmit"),
        NETWORK_TRANSMIT_RECEIVE("network-transmit-receive"),
        PRINTER_ERROR("printer-error"),
        PRINTER_PRINTING("printer-printing"),
        SECURITY_HIGH("security-high"),
        SECURITY_MEDIUM("security-medium"),
        SECURITY_LOW("security-low"),
        SOFTWARE_UPDATE_AVAILABLE("software-update-available"),
        SOFTWARE_UPDATE_URGENT("software-update-urgent"),
        SYNC_ERROR("sync-error"),
        SYNC_SYNCHRONIZING("sync-synchronizing"),
        TASK_DUE("task-due"),
        TASK_PAST_DUE("task-past-due"),
        USER_AVAILABLE("user-available"),
        USER_AWAY("user-away"),
        USER_IDLE("user-idle"),
        USER_OFFLINE("user-offline"),
        USER_TRASH_FULL("user-trash-full"),
        WEATHER_CLEAR("weather-clear"),
        WEATHER_CLEAR_NIGHT("weather-clear-night"),
        WEATHER_FEW_CLOUDS("weather-few-clouds"),
        WEATHER_FEW_CLOUDS_NIGHT("weather-few-clouds-night"),
        WEATHER_FOG("weather-fog"),
        WEATHER_OVERCAST("weather-overcast"),
        WEATHER_SEVERE_ALERT("weather-severe-alert"),
        WEATHER_SHOWERS("weather-showers"),
        WEATHER_SHOWERS_SCATTERED("weather-showers-scattered"),
        WEATHER_SNOW("weather-snow"),
        WEATHER_STORM("weather-storm"),
    }
}
