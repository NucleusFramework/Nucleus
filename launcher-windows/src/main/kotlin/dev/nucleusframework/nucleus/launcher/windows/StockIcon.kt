package dev.nucleusframework.nucleus.launcher.windows

/**
 * Type-safe mapping of Windows Shell Stock Icons ([SHSTOCKICONID]).
 *
 * These icons are provided by the Windows Shell and are available on all
 * Windows Vista+ systems. They can be used with [WindowsOverlayIcon] and
 * [WindowsThumbnailToolbar] without requiring external icon files.
 *
 * @param id The native SHSTOCKICONID value.
 */
@Suppress("MagicNumber")
enum class StockIcon(
    val id: Int,
) {
    // ---- Documents & Applications ----
    DOCUMENT_NO_ASSOCIATION(0),
    DOCUMENT_WITH_ASSOCIATION(1),
    APPLICATION(2),

    // ---- Folders ----
    FOLDER(3),
    FOLDER_OPEN(4),
    FOLDER_BACK(75),
    FOLDER_FRONT(76),
    STUFFED_FOLDER(57),

    // ---- Drives ----
    DRIVE_FLOPPY_525(5),
    DRIVE_FLOPPY_35(6),
    DRIVE_REMOVABLE(7),
    DRIVE_FIXED(8),
    DRIVE_NETWORK(9),
    DRIVE_NETWORK_DISABLED(10),
    DRIVE_CD(11),
    DRIVE_RAM(12),
    DRIVE_UNKNOWN(58),
    DRIVE_DVD(59),
    DRIVE_HD_DVD(132),
    DRIVE_BD(133),
    DRIVE_CLUSTERED(140),

    // ---- Network ----
    WORLD(13),
    SERVER(15),
    PRINTER(16),
    MY_NETWORK(17),
    PRINTER_NETWORK(50),
    SERVER_SHARE(51),
    PRINTER_FAX(52),
    PRINTER_FAX_NETWORK(53),
    PRINTER_FILE(54),
    NETWORK_CONNECT(103),
    INTERNET(104),

    // ---- System Actions ----
    FIND(22),
    HELP(23),
    SHARE(28),
    LINK(29),
    SLOW_FILE(30),
    RENAME(83),
    DELETE(84),

    // ---- Recycle Bin ----
    RECYCLER_EMPTY(31),
    RECYCLER_FULL(32),

    // ---- Security ----
    LOCK(47),
    SHIELD(77),
    KEY(81),

    // ---- Status ----
    WARNING(78),
    INFO(79),
    ERROR(80),

    // ---- Media Types ----
    MEDIA_CD_AUDIO(40),
    MEDIA_SVCD(56),
    MEDIA_DVD(60),
    MEDIA_DVD_RAM(61),
    MEDIA_DVD_RW(62),
    MEDIA_DVD_R(63),
    MEDIA_DVD_ROM(64),
    MEDIA_CD_AUDIO_PLUS(65),
    MEDIA_CD_RW(66),
    MEDIA_CD_R(67),
    MEDIA_CD_BURN(68),
    MEDIA_BLANK_CD(69),
    MEDIA_CD_ROM(70),
    MEDIA_AUDIO_DVD(85),
    MEDIA_MOVIE_DVD(86),
    MEDIA_ENHANCED_CD(87),
    MEDIA_ENHANCED_DVD(88),
    MEDIA_HD_DVD(89),
    MEDIA_BLURAY(90),
    MEDIA_VCD(91),
    MEDIA_DVD_PLUS_R(92),
    MEDIA_DVD_PLUS_RW(93),
    MEDIA_SMART_MEDIA(97),
    MEDIA_COMPACT_FLASH(98),
    MEDIA_HD_DVD_ROM(134),
    MEDIA_HD_DVD_R(135),
    MEDIA_HD_DVD_RAM(136),
    MEDIA_BD_ROM(137),
    MEDIA_BD_R(138),
    MEDIA_BD_RE(139),

    // ---- File Types ----
    AUDIO_FILES(71),
    IMAGE_FILES(72),
    VIDEO_FILES(73),
    MIXED_FILES(74),
    ZIP_FILE(105),

    // ---- Devices ----
    DESKTOP_PC(94),
    MOBILE_PC(95),
    DEVICE_CELL_PHONE(99),
    DEVICE_CAMERA(100),
    DEVICE_VIDEO_CAMERA(101),
    DEVICE_AUDIO_PLAYER(102),

    // ---- Miscellaneous ----
    USERS(96),
    STACK(55),
    AUTO_LIST(49),
    SOFTWARE(82),
    SETTINGS(106),
}
