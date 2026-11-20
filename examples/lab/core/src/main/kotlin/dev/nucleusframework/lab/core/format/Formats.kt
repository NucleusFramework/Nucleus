package dev.nucleusframework.lab.core.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Every value the Lab prints goes through here, in Locale.ROOT: the same reading on every
// machine, and copied text (TSV, reports) that parses back.

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

/** Wall-clock time of an event, to the millisecond. */
fun formatTime(epochMillis: Long): String = timeFormat.format(Instant.ofEpochMilli(epochMillis))

/** `512 B`, `1.5 KiB`, `3.2 GiB`. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "${value.fmt(1)} ${units[unit]}"
}

/** A duration for humans: `850 ms`, `12.4 s`, `3 min 05 s`, `2 h 03 min`, `4 d 2 h`. */
fun formatDurationMillis(millis: Long): String {
    val seconds = millis / 1000
    return when {
        millis < 1000 -> "$millis ms"
        millis < 60_000 -> "${(millis / 1000.0).fmt(1)} s"
        seconds < 3600 -> "%d min %02d s".format(Locale.ROOT, seconds / 60, seconds % 60)
        seconds < 86_400 -> "%d h %02d min".format(Locale.ROOT, seconds / 3600, seconds % 3600 / 60)
        else -> "%d d %d h".format(Locale.ROOT, seconds / 86_400, seconds % 86_400 / 3600)
    }
}

/** A media position: `1:05`, `1:02:09`. */
fun formatClock(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds % 3600 / 60, seconds % 60)
    } else {
        "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }
}

/** A fraction 0..1 as `42.0 %`. */
fun percent(
    fraction: Double,
    decimals: Int = 1,
): String = "${(fraction * 100).fmt(decimals)} %"

/** A value on a scale of [of] (OS APIs that answer 0..100) as `42.0 %`. */
fun percent(
    value: Double,
    of: Double,
    decimals: Int = 1,
): String = percent(value / of, decimals)

fun Float.fmt(decimals: Int = 2): String = "%.${decimals}f".format(Locale.ROOT, this)

fun Double.fmt(decimals: Int = 2): String = "%.${decimals}f".format(Locale.ROOT, this)

fun Float.signed(decimals: Int = 2): String = "%+.${decimals}f".format(Locale.ROOT, this)

/** `#AARRGGBB`. */
fun Long.argbHex(): String = "#%08X".format(Locale.ROOT, this and 0xFFFFFFFFL)

/** Lower-case hex digits, e.g. a SHA-256 or a certificate fingerprint. */
fun ByteArray.hex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

/** `IllegalStateException: no window` — how every failure reads in a probe. */
val Throwable.summary: String
    get() = "${this::class.simpleName}: ${message ?: "(no message)"}"
